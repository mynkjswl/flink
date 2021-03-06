/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.storm.wrappers;

import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;

import clojure.lang.Atom;
import org.apache.storm.Config;
import org.apache.storm.generated.Bolt;
import org.apache.storm.generated.ComponentCommon;
import org.apache.storm.generated.SpoutSpec;
import org.apache.storm.generated.StateSpoutSpec;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.IComponent;
import org.apache.storm.topology.IRichBolt;
import org.apache.storm.topology.IRichSpout;
import org.apache.storm.tuple.Fields;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * {@link WrapperSetupHelper} is an helper class used by {@link SpoutWrapper} and
 * {@link BoltWrapper}.
 */
class WrapperSetupHelper {

	/** The configuration key for the topology name. */
	static final String TOPOLOGY_NAME = "storm.topology.name";

	/**
	 * Computes the number of output attributes used by a {@link SpoutWrapper} or {@link BoltWrapper}
	 * per declared output stream. The number is {@code -1} for raw output type or a value within range [0;25] for
	 * output type {@link org.apache.flink.api.java.tuple.Tuple0 Tuple0} to
	 * {@link org.apache.flink.api.java.tuple.Tuple25 Tuple25}.
	 *
	 * @param spoutOrBolt
	 *            The Storm {@link IRichSpout spout} or {@link IRichBolt bolt} to be used.
	 * @param rawOutputs
	 *            Contains stream names if a single attribute output stream, should not be of type
	 *            {@link org.apache.flink.api.java.tuple.Tuple1 Tuple1} but be of a raw type. (Can be {@code null}.)
	 * @return The number of attributes to be used for each stream.
	 * @throws IllegalArgumentException
	 *             If {@code rawOutput} is {@code true} and the number of declared output attributes is not 1 or if
	 *             {@code rawOutput} is {@code false} and the number of declared output attributes is not with range
	 *             [0;25].
	 */
	static HashMap<String, Integer> getNumberOfAttributes(final IComponent spoutOrBolt,
			final Collection<String> rawOutputs)
					throws IllegalArgumentException {
		final SetupOutputFieldsDeclarer declarer = new SetupOutputFieldsDeclarer();
		spoutOrBolt.declareOutputFields(declarer);

		for (Entry<String, Integer> schema : declarer.outputSchemas.entrySet()) {
			int declaredNumberOfAttributes = schema.getValue();
			if ((declaredNumberOfAttributes < 0) || (declaredNumberOfAttributes > 25)) {
				throw new IllegalArgumentException(
						"Provided bolt declares non supported number of output attributes. Must be in range [0;25] but "
								+ "was " + declaredNumberOfAttributes);
			}

			if (rawOutputs != null && rawOutputs.contains(schema.getKey())) {
				if (declaredNumberOfAttributes != 1) {
					throw new IllegalArgumentException(
							"Ouput type is requested to be raw type, but provided bolt declares more then one output "
									+ "attribute.");
				}
				schema.setValue(-1);
			}
		}

		return declarer.outputSchemas;
	}

	/**
	 * Creates a {@link TopologyContext} for a Spout or Bolt instance (ie, Flink task / Storm executor).
	 *
	 * @param context
	 *            The Flink runtime context.
	 * @param spoutOrBolt
	 *            The Spout or Bolt this context is created for.
	 * @param stormConfig
	 *            The user provided configuration.
	 * @return The created {@link TopologyContext}.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	static synchronized TopologyContext createTopologyContext(
			final StreamingRuntimeContext context, final IComponent spoutOrBolt,
			final String operatorName, final Map stormConfig) {

		final int dop = context.getNumberOfParallelSubtasks();

		final Map<Integer, String> taskToComponents = new HashMap<Integer, String>();
		final Map<String, List<Integer>> componentToSortedTasks = new HashMap<String, List<Integer>>();
		final Map<String, Map<String, Fields>> componentToStreamToFields = new HashMap<String, Map<String, Fields>>();
		String stormId = (String) stormConfig.get(TOPOLOGY_NAME);
		String codeDir = null; // not supported
		String pidDir = null; // not supported
		Integer taskId = -1;
		Integer workerPort = null; // not supported
		List<Integer> workerTasks = new ArrayList<Integer>();
		final Map<String, Object> defaultResources = new HashMap<String, Object>();
		final Map<String, Object> userResources = new HashMap<String, Object>();
		final Map<String, Object> executorData = new HashMap<String, Object>();
		final Map registeredMetrics = new HashMap();
		Atom openOrPrepareWasCalled = null;

		ComponentCommon common = new ComponentCommon();
		common.set_parallelism_hint(dop);

		HashMap<String, SpoutSpec> spouts = new HashMap<String, SpoutSpec>();
		HashMap<String, Bolt> bolts = new HashMap<String, Bolt>();
		if (spoutOrBolt instanceof IRichSpout) {
			spouts.put(operatorName, new SpoutSpec(null, common));
		} else {
			assert (spoutOrBolt instanceof IRichBolt);
			bolts.put(operatorName, new Bolt(null, common));
		}
		StormTopology stormTopology = new StormTopology(spouts, bolts, new HashMap<String, StateSpoutSpec>());

		List<Integer> sortedTasks = new ArrayList<Integer>(dop);
		for (int i = 1; i <= dop; ++i) {
			taskToComponents.put(i, operatorName);
			sortedTasks.add(i);
		}
		componentToSortedTasks.put(operatorName, sortedTasks);

		SetupOutputFieldsDeclarer declarer = new SetupOutputFieldsDeclarer();
		spoutOrBolt.declareOutputFields(declarer);
		componentToStreamToFields.put(operatorName, declarer.outputStreams);

		if (!stormConfig.containsKey(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS)) {
			stormConfig.put(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS, 30); // Storm default value
		}

		return new FlinkTopologyContext(stormTopology, stormConfig, taskToComponents,
				componentToSortedTasks, componentToStreamToFields, stormId, codeDir, pidDir,
				taskId, workerPort, workerTasks, defaultResources, userResources, executorData,
				registeredMetrics, openOrPrepareWasCalled);
	}
}
