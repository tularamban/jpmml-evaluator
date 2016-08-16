/*
 * Copyright (c) 2015 Villu Ruusmann
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

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.common.base.Function;
import com.google.common.collect.Maps;

abstract
public class ClassificationAggregator<K> {

	private Map<K, DoubleVector> map = new LinkedHashMap<>();

	private int capacity = 0;


	public ClassificationAggregator(int capacity){
		this.capacity = capacity;
	}

	public int size(){
		return this.map.size();
	}

	public void add(K key, double value){
		DoubleVector values = this.map.get(key);

		if(values == null){
			values = new DoubleVector(this.capacity);

			this.map.put(key, values);
		}

		values.add(value);
	}

	public void clear(){
		this.map.clear();
	}

	protected <V> Map<K, V> transform(Function<DoubleVector, V> function){
		return Maps.transformValues(this.map, function);
	}
}