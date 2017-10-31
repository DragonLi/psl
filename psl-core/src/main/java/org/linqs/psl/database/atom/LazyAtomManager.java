/*
 * This file is part of the PSL software.
 * Copyright 2011-2015 University of Maryland
 * Copyright 2013-2017 The Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.linqs.psl.database.atom;

// TODO(eriq): Evaluate imports.

import org.linqs.psl.application.groundrulestore.GroundRuleStore;
import org.linqs.psl.config.ConfigBundle;
import org.linqs.psl.database.Database;
import org.linqs.psl.database.Partition;
import org.linqs.psl.database.ResultList;
import org.linqs.psl.database.rdbms.RDBMSDatabase;
import org.linqs.psl.database.rdbms.Formula2SQL;
import org.linqs.psl.model.Model;
import org.linqs.psl.model.atom.Atom;
import org.linqs.psl.model.atom.GroundAtom;
import org.linqs.psl.model.atom.RandomVariableAtom;
import org.linqs.psl.model.atom.VariableAssignment;
import org.linqs.psl.model.formula.Formula;
import org.linqs.psl.model.predicate.Predicate;
import org.linqs.psl.model.predicate.StandardPredicate;
import org.linqs.psl.model.term.Constant;
import org.linqs.psl.model.term.Variable;
import org.linqs.psl.model.term.VariableTypeMap;
import org.linqs.psl.model.rule.Rule;
import org.linqs.psl.model.rule.arithmetic.AbstractArithmeticRule;
import org.linqs.psl.model.rule.logical.AbstractLogicalRule;

import com.healthmarketscience.sqlbuilder.SelectQuery;
import com.healthmarketscience.sqlbuilder.SetOperationQuery;
import com.healthmarketscience.sqlbuilder.UnionQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A persisted atom manager that will keep track of atoms that it returns, but that
 * don't actually exist (lazy atoms).
 * If activateAtoms() is called, then all lazy atoms above the activation threshold
 * (set by the ACTIVATION_THRESHOLD_KEY configuration option) will be instantiated as
 * real atoms.
 */
public class LazyAtomManager extends PersistedAtomManager  {
	/**
	 * Prefix of property keys used by this class.
	 */
	public static final String CONFIG_PREFIX = "lazyatommanager";

	/**
	 * The minimum value an atom must take for it to be activated.
	 * Must be a flot in (0,1].
	 */
	public static final String ACTIVATION_THRESHOLD_KEY = CONFIG_PREFIX + ".activation";

	/**
	 * Default value for ACTIVATION_THRESHOLD_KEY property.
	 */
	public static final double ACTIVATION_THRESHOLD_DEFAULT = 0.01;

	private static final Logger log = LoggerFactory.getLogger(LazyAtomManager.class);

	/**
	 * All the ground atoms that have been seen, but not instantiated.
	 */
	private final Set<RandomVariableAtom> lazyAtoms;
	private final double activation;

	public LazyAtomManager(Database db, ConfigBundle config) {
		super(db);

		lazyAtoms = new HashSet<RandomVariableAtom>();
		activation = config.getDouble(ACTIVATION_THRESHOLD_KEY, ACTIVATION_THRESHOLD_DEFAULT);

		if (activation <= 0 || activation > 1) {
			throw new IllegalArgumentException(
					"Activation threshold must be in (0,1]." +
					" Got: " + activation + ".");
		}
	}

	@Override
	public GroundAtom getAtom(Predicate predicate, Constant... arguments) {
		RandomVariableAtom lazyAtom = null;

		try {
			return super.getAtom(predicate, arguments);
		} catch (PersistedAtomManager.PersistedAccessException ex) {
			lazyAtom = ex.atom;
		}

		lazyAtoms.add(lazyAtom);
		return lazyAtom;
	}

	public Set<RandomVariableAtom> getLazyAtoms() {
		return Collections.unmodifiableSet(lazyAtoms);
	}

	/**
	 * Activate any lazy atoms above the threshold.
	 * @return the number of lazy atoms instantiated.
	 */
	public int activateAtoms(Model model, GroundRuleStore groundRuleStore) {
		Set<RandomVariableAtom> toActivate = new HashSet<RandomVariableAtom>();

		Iterator<RandomVariableAtom> lazyAtomIterator = lazyAtoms.iterator();
		while (lazyAtomIterator.hasNext()) {
			RandomVariableAtom atom = lazyAtomIterator.next();

			if (atom.getValue() >= activation) {
				toActivate.add(atom);
				lazyAtomIterator.remove();
			}
		}

		activate(toActivate, model, groundRuleStore);
		return toActivate.size();
	}

	private void activate(Set<RandomVariableAtom> toActivate, Model model, GroundRuleStore groundRuleStore) {
		// First commit the atoms to the database.
		db.commit(toActivate, Partition.LAZY_PARTITION_ID);

		// Also ensure that the activated atoms are now considered "persisted" by the atom manager.
		addToPersistedCache(toActivate);

		// Now, we need to do a partial regrounding with the activated atoms.

		// Collect the specific predicates that are targets in this lazy batch
		// and the rules associated with those predicates.
		Set<StandardPredicate> lazyPredicates = getLazyPredicates(toActivate);
		Set<Rule> lazyRules = getLazyRules(model, lazyPredicates);

		for (Rule lazyRule : lazyRules) {
			// TODO(eriq): Arithmetic
			AbstractLogicalRule rule = (AbstractLogicalRule)lazyRule;

			Formula formula = rule.getDNF().getQueryFormula();
			List<Atom> lazyTargets = new ArrayList<Atom>();

			// For every mention of a lazy predicate in this rule, we will need to get the grounding query
			// with that specific predicate mention being the lazy target.
			for (Atom atom : formula.getAtoms(new HashSet<Atom>())) {
				if (!lazyPredicates.contains(atom.getPredicate())) {
					continue;
				}

				lazyTargets.add(atom);
			}

			if (lazyTargets.size() == 0) {
				continue;
			}

			// Do the grounding query for this rule.
			ResultList groundingResults = lazyGround(formula, lazyTargets, groundRuleStore);

			rule.groundAll(groundingResults, this, groundRuleStore);
		}

		// Move all the new atoms out of the lazy partition and into the write partition.
		for (StandardPredicate lazyPredicate : lazyPredicates) {
			db.moveToWritePartition(lazyPredicate, Partition.LAZY_PARTITION_ID);
		}
	}


	private ResultList lazyGround(Formula formula, List<Atom> lazyTargets, GroundRuleStore groundRuleStore) {
		if (lazyTargets.size() == 0) {
			throw new IllegalArgumentException();
		}

		// TODO(eriq): Force super to have RDBMS?
		RDBMSDatabase relationalDB = ((RDBMSDatabase)db);

		List<SelectQuery> queries = new ArrayList<SelectQuery>();

		VariableAssignment partialGrounding = new VariableAssignment();
		VariableTypeMap varTypes = formula.collectVariables(new VariableTypeMap());
		Map<Variable, Integer> projectionMap = null;

		for (Atom lazyTarget : lazyTargets) {
			Formula2SQL sqler = new Formula2SQL(partialGrounding, varTypes.getVariables(), relationalDB, false, lazyTarget);
			queries.add(sqler.getQuery(formula));

			if (projectionMap == null) {
				projectionMap = sqler.getProjectionMap();
			}
		}

		// This fallbacks to a normal SELECT when there is only one.
		UnionQuery union = new UnionQuery(SetOperationQuery.Type.UNION, queries.toArray(new SelectQuery[0]));
		return relationalDB.executeQuery(partialGrounding, projectionMap, varTypes, union.validate().toString());
	}

	private Set<StandardPredicate> getLazyPredicates(Set<RandomVariableAtom> toActivate) {
		Set<StandardPredicate> lazyPredicates = new HashSet<StandardPredicate>();
		for (Atom atom : toActivate) {
			if (atom.getPredicate() instanceof StandardPredicate) {
				lazyPredicates.add((StandardPredicate)atom.getPredicate());
			}
		}
		return lazyPredicates;
	}

	private Set<Rule> getLazyRules(Model model, Set<StandardPredicate> lazyPredicates) {
		Set<Rule> lazyRules = new HashSet<Rule>();

		for (Rule rule : model.getRules()) {
			if (rule instanceof AbstractLogicalRule) {
				// Note that we check for atoms not in the base formula, but in the
				// query formula for the DNF because negated atoms will not
				// be considered.
				for (Atom atom : ((AbstractLogicalRule)rule).getDNF().getQueryFormula().getAtoms(new HashSet<Atom>())) {
					if (lazyPredicates.contains(atom.getPredicate())) {
						lazyRules.add(rule);
						break;
					}
				}
			}

			// TODO(eriq): Arithmetic
		}

		return lazyRules;
	}
}