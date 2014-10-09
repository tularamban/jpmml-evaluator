/*
 * Copyright (c) 2013 Villu Ruusmann
 *
 * This file is part of JPMML-Evaluator
 *
 * JPMML-Evaluator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Evaluator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Evaluator.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.evaluator;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import org.dmg.pmml.CompoundPredicate;
import org.dmg.pmml.DataType;
import org.dmg.pmml.EmbeddedModel;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.MiningFunctionType;
import org.dmg.pmml.MissingValueStrategyType;
import org.dmg.pmml.NoTrueChildStrategyType;
import org.dmg.pmml.Node;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.ScoreDistribution;
import org.dmg.pmml.TreeModel;
import org.jpmml.manager.InvalidFeatureException;
import org.jpmml.manager.UnsupportedFeatureException;

public class TreeModelEvaluator extends ModelEvaluator<TreeModel> implements HasEntityRegistry<Node> {

	public TreeModelEvaluator(PMML pmml){
		this(pmml, find(pmml.getModels(), TreeModel.class));
	}

	public TreeModelEvaluator(PMML pmml, TreeModel treeModel){
		super(pmml, treeModel);
	}

	@Override
	public String getSummary(){
		return "Tree model";
	}

	@Override
	public BiMap<String, Node> getEntityRegistry(){
		return getValue(TreeModelEvaluator.entityCache);
	}

	@Override
	public Map<FieldName, ?> evaluate(ModelEvaluationContext context){
		TreeModel treeModel = getModel();
		if(!treeModel.isScorable()){
			throw new InvalidResultException(treeModel);
		}

		Map<FieldName, ?> predictions;

		MiningFunctionType miningFunction = treeModel.getFunctionName();
		switch(miningFunction){
			case REGRESSION:
				predictions = evaluateRegression(context);
				break;
			case CLASSIFICATION:
				predictions = evaluateClassification(context);
				break;
			default:
				throw new UnsupportedFeatureException(treeModel, miningFunction);
		}

		return OutputUtil.evaluate(predictions, context);
	}

	private Map<FieldName, ? extends Number> evaluateRegression(ModelEvaluationContext context){
		Double result = null;

		Trail trail = new Trail();

		Node node = evaluateTree(trail, context);
		if(node != null){
			String score = ensureScore(node);

			result = (Double)TypeUtil.parseOrCast(DataType.DOUBLE, score);
		}

		return TargetUtil.evaluateRegression(result, context);
	}

	private Map<FieldName, ? extends ClassificationMap<?>> evaluateClassification(ModelEvaluationContext context){
		TreeModel treeModel = getModel();

		NodeClassificationMap result = null;

		Trail trail = new Trail();

		Node node = evaluateTree(trail, context);
		if(node != null){
			ensureScore(node);

			double missingValuePenalty = 1d;

			int missingLevels = trail.getMissingLevels();
			for(int i = 0; i < missingLevels; i++){
				missingValuePenalty *= treeModel.getMissingValuePenalty();
			}

			result = createNodeClassificationMap(node, missingValuePenalty);
		}

		return TargetUtil.evaluateClassification(result, context);
	}

	private Node evaluateTree(Trail trail, ModelEvaluationContext context){
		TreeModel treeModel = getModel();

		Node root = treeModel.getNode();
		if(root == null){
			throw new InvalidFeatureException(treeModel);
		}

		Boolean status = evaluateNode(root, trail, context);

		NodeResult result = null;

		if(status == null){
			result = handleMissingValue(root, trail, context);
		} else

		if(status.booleanValue()){
			result = handleTrue(root, null, trail, context);
		} // End if

		if(result != null && result.isFinal()){
			return result.getNode();
		}

		NoTrueChildStrategyType noTrueChildStrategy = treeModel.getNoTrueChildStrategy();
		switch(noTrueChildStrategy){
			case RETURN_NULL_PREDICTION:
				return null;
			case RETURN_LAST_PREDICTION:
				if(trail.size() > 0){
					Node parent = trail.getFirst();

					// "Return the parent Node only if it specifies a score attribute"
					if(parent.getScore() != null){
						return parent;
					}
				}
				return null;
			default:
				throw new UnsupportedFeatureException(treeModel, noTrueChildStrategy);
		}
	}

	private Boolean evaluateNode(Node node, Trail trail, EvaluationContext context){
		EmbeddedModel embeddedModel = node.getEmbeddedModel();
		if(embeddedModel != null){
			throw new UnsupportedFeatureException(embeddedModel);
		}

		Predicate predicate = node.getPredicate();
		if(predicate == null){
			throw new InvalidFeatureException(node);
		} // End if

		// A compound predicate whose boolean operator is "surrogate" represents a special case
		if(predicate instanceof CompoundPredicate){
			CompoundPredicate compoundPredicate = (CompoundPredicate)predicate;

			PredicateUtil.CompoundPredicateResult result = PredicateUtil.evaluateCompoundPredicateInternal(compoundPredicate, context);
			if(result.isAlternative()){
				trail.addMissingLevel();
			}

			return result.getResult();
		} else

		{
			return PredicateUtil.evaluate(predicate, context);
		}
	}

	/**
	 * @param id The {@link Node#getId id} of the first child Node to consider. If <code>null</code>, all child Nodes are considered.
	 */
	private NodeResult handleTrue(Node node, String id, Trail trail, EvaluationContext context){
		List<Node> children = node.getNodes();

		// A "true" leaf node
		if(children.isEmpty()){
			return new NodeResult(node);
		}

		trail.push(node);

		for(Node child : children){

			if(id != null){

				if(!(id).equals(child.getId())){
					continue;
				}

				id = null;
			}

			Boolean status = evaluateNode(child, trail, context);

			if(status == null){
				NodeResult result = handleMissingValue(child, trail, context);

				if(result != null){
					return result;
				}
			} else

			if(status.booleanValue()){
				return handleTrue(child, null, trail, context);
			}
		}

		if(id != null){
			throw new InvalidFeatureException(node);
		}

		// A branch node with no "true" leaf nodes
		return new NodeResult(null);
	}

	private NodeResult handleMissingValue(Node node, Trail trail, EvaluationContext context){
		TreeModel treeModel = getModel();

		MissingValueStrategyType missingValueStrategy = treeModel.getMissingValueStrategy();
		switch(missingValueStrategy){
			case NULL_PREDICTION:
				return new FinalNodeResult(null);
			case LAST_PREDICTION:
				return new FinalNodeResult(trail.getFirst());
			case DEFAULT_CHILD:
				List<Node> children = node.getNodes();

				if(children.isEmpty()){
					return null;
				}

				// "The defaultChild missing value strategy requires the presence of the defaultChild attribute in every non-leaf Node"
				String defaultChild = node.getDefaultChild();
				if(defaultChild == null){
					throw new InvalidFeatureException(node);
				}

				trail.addMissingLevel();

				return handleTrue(node, defaultChild, trail, context);
			case NONE:
				return null;
			default:
				throw new UnsupportedFeatureException(treeModel, missingValueStrategy);
		}
	}

	static
	private String ensureScore(Node node){
		String score = node.getScore();

		// "It is not possible that the scoring process ends in a Node which does not have a score attribute"
		if(score == null){
			throw new InvalidFeatureException(node);
		}

		return score;
	}

	static
	private NodeClassificationMap createNodeClassificationMap(Node node, double missingValuePenalty){
		NodeClassificationMap result = new NodeClassificationMap(node);

		List<ScoreDistribution> scoreDistributions = node.getScoreDistributions();

		double sum = 0;

		for(ScoreDistribution scoreDistribution : scoreDistributions){
			sum += scoreDistribution.getRecordCount();
		} // End for

		for(ScoreDistribution scoreDistribution : scoreDistributions){
			Double value = scoreDistribution.getProbability();
			if(value == null){
				value = (scoreDistribution.getRecordCount() / sum);
			}

			result.put(scoreDistribution.getValue(), value);

			Double confidence = scoreDistribution.getConfidence();
			if(confidence != null){
				result.putConfidence(scoreDistribution.getValue(), confidence * missingValuePenalty);
			}
		}

		return result;
	}

	static
	private class Trail extends ArrayDeque<Node> {

		private int missingLevels = 0;


		public Trail(){
		}

		public void addMissingLevel(){
			setMissingLevels(getMissingLevels() + 1);
		}

		public int getMissingLevels(){
			return this.missingLevels;
		}

		private void setMissingLevels(int missingLevels){
			this.missingLevels = missingLevels;
		}
	}

	static
	private class NodeResult {

		private Node node = null;


		public NodeResult(Node node){
			setNode(node);
		}

		/**
		 * @return <code>true</code> if the result should be exempt from any post-processing (eg. "no true child strategy" treatment), <code>false</code> otherwise.
		 */
		public boolean isFinal(){
			Node node = getNode();

			return (node != null);
		}

		public Node getNode(){
			return this.node;
		}

		private void setNode(Node node){
			this.node = node;
		}
	}

	static
	private class FinalNodeResult extends NodeResult {

		public FinalNodeResult(Node node){
			super(node);
		}

		@Override
		public boolean isFinal(){
			return true;
		}
	}

	private static final LoadingCache<TreeModel, BiMap<String, Node>> entityCache = CacheBuilder.newBuilder()
		.weakKeys()
		.build(new CacheLoader<TreeModel, BiMap<String, Node>>(){

			@Override
			public BiMap<String, Node> load(TreeModel treeModel){
				ImmutableBiMap.Builder<String, Node> builder = new ImmutableBiMap.Builder<String, Node>();

				builder = collectNodes(treeModel.getNode(), builder);

				return builder.build();
			}

			private ImmutableBiMap.Builder<String, Node> collectNodes(Node node, ImmutableBiMap.Builder<String, Node> builder){
				builder = EntityUtil.put(node, builder);

				List<Node> children = node.getNodes();
				for(Node child : children){
					builder = collectNodes(child, builder);
				}

				return builder;
			}
		});
}