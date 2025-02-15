/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License") you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.processors.elasticsearch

import org.apache.nifi.components.state.Scope
import org.apache.nifi.flowfile.FlowFile
import org.apache.nifi.provenance.ProvenanceEventType
import org.apache.nifi.util.MockFlowFile
import org.apache.nifi.util.TestRunner
import org.apache.nifi.util.TestRunners
import org.junit.Assert
import org.junit.Test

import static groovy.json.JsonOutput.prettyPrint
import static groovy.json.JsonOutput.toJson
import static org.hamcrest.CoreMatchers.equalTo
import static org.hamcrest.CoreMatchers.is
import static org.hamcrest.MatcherAssert.assertThat
import static org.junit.Assert.assertThrows

abstract class AbstractJsonQueryElasticsearchTest<P extends AbstractJsonQueryElasticsearch> {
    static final String INDEX_NAME = "messages"

    abstract P getProcessor()

    abstract boolean isStateUsed()

    abstract boolean isInput()

    @Test
    void testMandatoryProperties() {
        final TestRunner runner = createRunner(false)
        runner.removeProperty(AbstractJsonQueryElasticsearch.CLIENT_SERVICE)
        runner.removeProperty(AbstractJsonQueryElasticsearch.INDEX)
        runner.removeProperty(AbstractJsonQueryElasticsearch.TYPE)
        runner.removeProperty(AbstractJsonQueryElasticsearch.QUERY)
        runner.removeProperty(AbstractJsonQueryElasticsearch.QUERY_ATTRIBUTE)
        runner.removeProperty(AbstractJsonQueryElasticsearch.AGGREGATION_RESULTS_SPLIT)
        runner.removeProperty(AbstractJsonQueryElasticsearch.SEARCH_RESULTS_SPLIT)

        final AssertionError assertionError = assertThrows(AssertionError.class, runner.&run)
        if (processor instanceof SearchElasticsearch) {
            assertThat(assertionError.getMessage(), equalTo(String.format("Processor has 3 validation failures:\n" +
                    "'%s' is invalid because %s is required\n" +
                    "'%s' is invalid because %s is required\n" +
                    "'%s' is invalid because %s is required\n",
                    AbstractJsonQueryElasticsearch.QUERY.getDisplayName(), AbstractJsonQueryElasticsearch.QUERY.getDisplayName(),
                    AbstractJsonQueryElasticsearch.INDEX.getDisplayName(), AbstractJsonQueryElasticsearch.INDEX.getDisplayName(),
                    AbstractJsonQueryElasticsearch.CLIENT_SERVICE.getDisplayName(), AbstractJsonQueryElasticsearch.CLIENT_SERVICE.getDisplayName()
            )))
        } else {
            assertThat(assertionError.getMessage(), equalTo(String.format("Processor has 2 validation failures:\n" +
                    "'%s' is invalid because %s is required\n" +
                    "'%s' is invalid because %s is required\n",
                    AbstractJsonQueryElasticsearch.INDEX.getDisplayName(), AbstractJsonQueryElasticsearch.INDEX.getDisplayName(),
                    AbstractJsonQueryElasticsearch.CLIENT_SERVICE.getDisplayName(), AbstractJsonQueryElasticsearch.CLIENT_SERVICE.getDisplayName()
            )))
        }
    }

    @Test
    void testInvalidProperties() {
        final TestRunner runner = createRunner(false)
        runner.setProperty(AbstractJsonQueryElasticsearch.CLIENT_SERVICE, "not-a-service")
        runner.setProperty(AbstractJsonQueryElasticsearch.INDEX, "")
        runner.setProperty(AbstractJsonQueryElasticsearch.TYPE, "")
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, "not-json")
        runner.setProperty(AbstractJsonQueryElasticsearch.AGGREGATION_RESULTS_SPLIT, "not-enum")
        runner.setProperty(AbstractJsonQueryElasticsearch.SEARCH_RESULTS_SPLIT, "not-enum2")

        final String expectedAllowedSplitHits = processor instanceof AbstractPaginatedJsonQueryElasticsearch
            ? [AbstractJsonQueryElasticsearch.FLOWFILE_PER_RESPONSE, AbstractJsonQueryElasticsearch.FLOWFILE_PER_HIT, AbstractPaginatedJsonQueryElasticsearch.FLOWFILE_PER_QUERY].join(", ")
            : [AbstractJsonQueryElasticsearch.FLOWFILE_PER_RESPONSE, AbstractJsonQueryElasticsearch.FLOWFILE_PER_HIT].join(", ")

        final AssertionError assertionError = assertThrows(AssertionError.class, runner.&run)
        assertThat(assertionError.getMessage(), equalTo(String.format("Processor has 7 validation failures:\n" +
                "'%s' validated against 'not-json' is invalid because %s is not a valid JSON representation due to Unrecognized token 'not': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')\n" +
                " at [Source: (String)\"not-json\"; line: 1, column: 4]\n" +
                "'%s' validated against '' is invalid because %s cannot be empty\n" +
                "'%s' validated against '' is invalid because %s cannot be empty\n" +
                "'%s' validated against 'not-a-service' is invalid because Property references a Controller Service that does not exist\n" +
                "'%s' validated against 'not-enum2' is invalid because Given value not found in allowed set '%s'\n" +
                "'%s' validated against 'not-enum' is invalid because Given value not found in allowed set '%s'\n" +
                "'%s' validated against 'not-a-service' is invalid because Invalid Controller Service: not-a-service is not a valid Controller Service Identifier\n",
                AbstractJsonQueryElasticsearch.QUERY.getName(), AbstractJsonQueryElasticsearch.QUERY.getName(),
                AbstractJsonQueryElasticsearch.INDEX.getName(), AbstractJsonQueryElasticsearch.INDEX.getName(),
                AbstractJsonQueryElasticsearch.TYPE.getName(), AbstractJsonQueryElasticsearch.TYPE.getName(),
                AbstractJsonQueryElasticsearch.CLIENT_SERVICE.getDisplayName(),
                AbstractJsonQueryElasticsearch.SEARCH_RESULTS_SPLIT.getName(), expectedAllowedSplitHits,
                AbstractJsonQueryElasticsearch.AGGREGATION_RESULTS_SPLIT.getName(), [AbstractJsonQueryElasticsearch.FLOWFILE_PER_RESPONSE, AbstractJsonQueryElasticsearch.FLOWFILE_PER_HIT].join(", "),
                AbstractJsonQueryElasticsearch.CLIENT_SERVICE.getDisplayName()
        )))
    }

    @Test
    void testBasicQuery() throws Exception {
        // test hits (no splitting)
        final TestRunner runner = createRunner(false)
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, prettyPrint(toJson([query: [ match_all: [:] ]])))
        runOnce(runner)
        testCounts(runner, isInput() ? 1 : 0, 1, 0, 0)
        final FlowFile hits = runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_HITS).get(0)
        hits.assertAttributeEquals("hit.count", "10")
        assertOutputContent(hits.getContent(), 10, false)
        assertThat(
                runner.getProvenanceEvents().stream().filter({ pe ->
                    pe.getEventType() == ProvenanceEventType.RECEIVE &&
                            pe.getAttribute("uuid") == hits.getAttribute("uuid")
                }).count(),
                is(1L)
        )
        reset(runner)


        // test splitting hits
        runner.setProperty(AbstractJsonQueryElasticsearch.SEARCH_RESULTS_SPLIT, AbstractJsonQueryElasticsearch.FLOWFILE_PER_HIT)
        runOnce(runner)
        testCounts(runner, isInput() ? 1 : 0, 10, 0, 0)
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_HITS).forEach(
                { hit ->
                    hit.assertAttributeEquals("hit.count", "1")
                    assertOutputContent(hit.getContent(), 1, false)
                    assertThat(
                            runner.getProvenanceEvents().stream().filter({ pe ->
                                pe.getEventType() == ProvenanceEventType.RECEIVE &&
                                        pe.getAttribute("uuid") == hit.getAttribute("uuid")
                            }).count(),
                            is(1L)
                    )
                }
        )
    }

    @Test
    void testAggregations() throws Exception {
        String query = prettyPrint(toJson([
                query: [ match_all: [:] ],
                aggs: [ term_agg: [ terms: [ field: "msg" ] ], term_agg2: [ terms: [ field: "msg" ] ] ]
        ]))

        // test aggregations (no splitting)
        final TestRunner runner = createRunner(true)
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, query)
        runOnce(runner)
        testCounts(runner, isInput() ? 1 : 0, 1, 0, 1)
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_HITS).get(0).assertAttributeEquals("hit.count", "10")
        FlowFile aggregations = runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_AGGREGATIONS).get(0)
        aggregations.assertAttributeNotExists("aggregation.number")
        aggregations.assertAttributeNotExists("aggregation.name")
        // count == 1 because aggregations is a single Map rather than a List of Maps, even when there are multiple aggs
        assertOutputContent(aggregations.getContent(), 1, false)
        reset(runner)


        // test with the query parameter and no incoming connection
        runner.setIncomingConnection(false)
        runner.run(1, true, true)
        testCounts(runner, 0, 1, 0, 1)
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_HITS).get(0).assertAttributeEquals("hit.count", "10")
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_AGGREGATIONS).get(0).assertAttributeNotExists("aggregation.number")
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_AGGREGATIONS).get(0).assertAttributeNotExists("aggregation.name")
        reset(runner)


        // test splitting aggregations
        runner.setProperty(AbstractJsonQueryElasticsearch.AGGREGATION_RESULTS_SPLIT, AbstractJsonQueryElasticsearch.FLOWFILE_PER_HIT)
        runOnce(runner)
        testCounts(runner, isInput() ? 1 : 0, 1, 0, 2)
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_HITS).get(0).assertAttributeEquals("hit.count", "10")
        int a = 0
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_AGGREGATIONS).forEach(
                { agg ->
                    agg.assertAttributeEquals("aggregation.name", a == 0 ? "term_agg" : "term_agg2")
                    agg.assertAttributeEquals("aggregation.number", Integer.toString(++a))
                    assertOutputContent(agg.getContent(), 1, false)
                }
        )
        reset(runner)


        // test using Expression Language (index, type, query)
        query = prettyPrint(toJson([
                query: [ match_all: [:] ],
                aggs: [ term_agg: [ terms: [ field: "\${fieldValue}" ] ], term_agg2: [ terms: [ field: "\${fieldValue}" ] ] ]
        ]))
        runner.setVariable("fieldValue", "msg")
        runner.setVariable("es.index", INDEX_NAME)
        runner.setVariable("es.type", "msg")
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, query)
        runner.setProperty(AbstractJsonQueryElasticsearch.INDEX, "\${es.index}")
        runner.setProperty(AbstractJsonQueryElasticsearch.TYPE, "\${es.type}")
        runOnce(runner)
        testCounts(runner, isInput() ? 1 : 0, 1, 0, 2)
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_HITS).get(0).assertAttributeEquals("hit.count", "10")
        a = 0
        runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_AGGREGATIONS).forEach(
                { agg ->
                    agg.assertAttributeEquals("aggregation.name", a == 0 ? "term_agg" : "term_agg2")
                    agg.assertAttributeEquals("aggregation.number", Integer.toString(++a))
                    assertOutputContent(agg.getContent(), 1, false)
                }
        )
    }

    @Test
    void testErrorDuringSearch() throws Exception {
        String query = prettyPrint(toJson([
                query: [ match_all: [:] ],
                aggs: [ term_agg: [ terms: [ field: "msg" ] ], term_agg2: [ terms: [ field: "msg" ] ] ]
        ]))

        final TestRunner runner = createRunner(true)
        getService(runner).setThrowErrorInSearch(true)
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, query)
        runOnce(runner)
        testCounts(runner, 0, 0, isInput() ? 1 : 0, 0)
    }

    @Test
    void testQueryAttribute() throws Exception {
        String query = prettyPrint(toJson([
                query: [ match_all: [:] ],
                aggs: [ term_agg: [ terms: [ field: "msg" ] ], term_agg2: [ terms: [ field: "msg" ] ] ]
        ]))
        final String queryAttr = "es.query"

        final TestRunner runner = createRunner(true)
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, query)
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY_ATTRIBUTE, queryAttr)
        runOnce(runner)
        testCounts(runner, isInput() ? 1 : 0, 1, 0, 1)
        final List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_AGGREGATIONS)
        flowFiles.addAll(runner.getFlowFilesForRelationship(AbstractJsonQueryElasticsearch.REL_HITS))

        for (final MockFlowFile mockFlowFile : flowFiles) {
            final String attr = mockFlowFile.getAttribute(queryAttr)
            Assert.assertNotNull("Missing query attribute", attr)
            Assert.assertEquals("Query had wrong value.", query, attr)
        }
    }

    @Test
    void testInputHandling() {
        final TestRunner runner = createRunner(false)
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, prettyPrint(toJson([query: [ match_all: [:] ]])))

        runner.setIncomingConnection(true)
        runner.run()
        testCounts(runner, 0, 0, 0, 0)
        reset(runner)

        runner.setIncomingConnection(false)
        runner.run()
        testCounts(runner, 0, 1, 0, 0)
    }

    @Test
    void testRequestParameters() {
        final TestRunner runner = createRunner(false)
        runner.setProperty(AbstractJsonQueryElasticsearch.QUERY, prettyPrint(toJson([query: [ match_all: [:] ]])))
        runner.setProperty("refresh", "true")
        runner.setProperty("slices", '${slices}')
        runner.setVariable("slices", "auto")

        runOnce(runner)

        final TestElasticsearchClientService service = runner.getControllerService("esService") as TestElasticsearchClientService
        if (getProcessor() instanceof SearchElasticsearch || getProcessor() instanceof PaginatedJsonQueryElasticsearch) {
            Assert.assertEquals(3, service.getRequestParameters().size())
            Assert.assertEquals("600s", service.getRequestParameters().get("scroll"))
        } else {
            Assert.assertEquals(2, service.getRequestParameters().size())
        }
        Assert.assertEquals("true", service.getRequestParameters().get("refresh"))
        Assert.assertEquals("auto", service.getRequestParameters().get("slices"))
    }

    static void testCounts(TestRunner runner, int original, int hits, int failure, int aggregations) {
        runner.assertTransferCount(AbstractJsonQueryElasticsearch.REL_ORIGINAL, original)
        runner.assertTransferCount(AbstractJsonQueryElasticsearch.REL_HITS, hits)
        runner.assertTransferCount(AbstractJsonQueryElasticsearch.REL_FAILURE, failure)
        runner.assertTransferCount(AbstractJsonQueryElasticsearch.REL_AGGREGATIONS, aggregations)
        runner.assertTransferCount(AbstractJsonQueryElasticsearch.REL_RETRY, 0)
    }

    static void assertOutputContent(final String content, final int count, final boolean ndjson) {
        if (ndjson) {
            assertThat(content.split("\n").length, is(count))
        } else {
            if (count == 1) {
                assertThat(content.startsWith("{") && content.endsWith("}"), is(true))
            } else {
                assertThat(content.startsWith("[") && content.endsWith("]"), is(true))
            }
        }
    }

    TestRunner createRunner(final boolean returnAggs) {
        final P processor = getProcessor()
        final TestRunner runner = TestRunners.newTestRunner(processor)
        final TestElasticsearchClientService service = new TestElasticsearchClientService(returnAggs)
        runner.addControllerService("esService", service)
        runner.enableControllerService(service)
        runner.setProperty(AbstractJsonQueryElasticsearch.CLIENT_SERVICE, "esService")
        runner.setProperty(AbstractJsonQueryElasticsearch.INDEX, INDEX_NAME)
        runner.setProperty(AbstractJsonQueryElasticsearch.TYPE, "message")
        runner.setValidateExpressionUsage(true)

        return runner
    }

    MockFlowFile runOnce(final TestRunner runner) {
        final MockFlowFile ff
        if (isInput()) {
            runner.setIncomingConnection(true)
            ff = runner.enqueue("test")
        } else {
            runner.setIncomingConnection(false)
            ff = null
        }

        runner.run(1, true, true)
        return ff
    }

    static TestElasticsearchClientService getService(final TestRunner runner) {
        return runner.getControllerService("esService", TestElasticsearchClientService.class)
    }

    void reset(final TestRunner runner) {
        runner.clearProvenanceEvents()
        runner.clearTransferState()
        if (isStateUsed()) {
            runner.getStateManager().clear(Scope.LOCAL)
        }

        getService(runner).resetPageCount()
    }
}
