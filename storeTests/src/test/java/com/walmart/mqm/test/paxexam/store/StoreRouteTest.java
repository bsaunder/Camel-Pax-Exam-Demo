package com.walmart.mqm.test.paxexam.store;

import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.logLevel;

import java.io.File;
import java.net.ConnectException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.apache.karaf.features.FeaturesService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.ProbeBuilder;
import org.ops4j.pax.exam.TestProbeBuilder;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.karaf.options.KarafDistributionOption;
import org.ops4j.pax.exam.karaf.options.LogLevelOption.LogLevel;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

import com.walmart.mqm.test.paxexam.util.ExceptionProcessor;
import com.walmart.mqm.test.paxexam.util.PaxExamTestUtil;

@RunWith(PaxExam.class)
public class StoreRouteTest extends CamelTestSupport {

    private ExecutorService executor = Executors.newCachedThreadPool();

    @Inject
    private FeaturesService featuresService;

    @Inject
    private BundleContext bundleContext;

    private CamelContext camelContext;

    // This should be the Name of the Camel Context you are Testing
    private static final String CAMEL_CONTEXT_NAME = "infraMessaging";

    @Configuration
    public static Option[] configure() throws Exception {
        return new Option[] {
                karafDistributionConfiguration()
                        .frameworkUrl(
                                maven().groupId("org.apache.karaf").artifactId("apache-karaf").type("zip")
                                        .versionAsInProject()).useDeployFolder(false).karafVersion("3.0.0")
                        .unpackDirectory(new File("target/paxexam/unpack/")),

                logLevel(LogLevel.WARN),

                features(
                        maven().groupId("org.apache.camel.karaf").artifactId("apache-camel").type("xml")
                                .classifier("features").versionAsInProject(), "camel-blueprint", "camel-jms",
                        "camel-jpa", "camel-mvel", "camel-jdbc", "camel-cxf", "camel-test"),

                KarafDistributionOption.editConfigurationFilePut("etc/org.ops4j.pax.url.mvn.cfg",
                        "org.ops4j.pax.url.mvn.proxySupport", "true"),
                keepRuntimeFolder(),
                KarafDistributionOption.replaceConfigurationFile("etc/com.walmart.mqm.store.routes.cfg", new File(
                        "src/test/resources/com.walmart.mqm.store.routes.cfg")),

                mavenBundle().groupId("com.walmart.mqm").artifactId("storeBundle").versionAsInProject() };
    }

    @ProbeBuilder
    public TestProbeBuilder probeConfiguration(TestProbeBuilder probe) {
        // makes sure the generated Test-Bundle contains this import!
        probe.setHeader(Constants.DYNAMICIMPORT_PACKAGE,
                "com.walmart.mqm,*,org.apache.felix.service.*;status=provisional");
        return probe;
    }

    @Override
    public boolean isCreateCamelContextPerClass() {
        // we override this method and return true, to tell Camel test-kit that
        // it should only create CamelContext once (per class), so we will
        // re-use the CamelContext between each test method in this class
        return true;
    }

    @Override
    protected void doPreSetup() throws Exception {
        camelContext = PaxExamTestUtil.getOsgiService(CamelContext.class, "(camel.context.name=" + CAMEL_CONTEXT_NAME
                + ")", 10000, bundleContext);
        assertNotNull(camelContext);
    }

    @Before
    public void testSetup() throws Exception {
        // Assert Camel Features Installed
        assertTrue(featuresService.isInstalled(featuresService.getFeature("camel-core")));
        assertTrue(featuresService.isInstalled(featuresService.getFeature("camel-blueprint")));

        // Assert Bundle is Activated
        PaxExamTestUtil.assertBundleActive("com.walmart.mqm.storeBundle", bundleContext);

        // Assert Camel Context is Found
        String contextListCmd = PaxExamTestUtil.executeCommand("camel:context-list", executor, bundleContext);
        // System.out.println(contextListCmd);
        assertTrue("Doesn't contain desired camel-context", contextListCmd.contains(CAMEL_CONTEXT_NAME));

        // This code is useful for Debugging a Routes Tests
        /*
         * String routeListCmd = PaxExamTestUtil.executeCommand("camel:route-list", executor, bundleContext);
         * System.out.println(routeListCmd);
         * 
         * String routeInfoCmd = PaxExamTestUtil.executeCommand("camel:route-info storeToGW01", executor,
         * bundleContext); System.out.println(routeInfoCmd);
         */
    }

    /**
     * Tests The Happy Path Route with No Header Sent.
     * 
     * @throws Exception
     */
    @Test
    public void ifNoHeaderThenIdIsJmsId() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGw01 = (MockEndpoint) camelContext.getEndpoint("mock:store_out");
        MockEndpoint mockGw01Dlq = (MockEndpoint) camelContext.getEndpoint("mock:store_dlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGw01.expectedMessageCount(1);
        mockGw01.expectedBodiesReceived("Hello from Camel");

        // Should be Nothing on the DLQ
        mockGw01Dlq.expectedMessageCount(0);

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gw01_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
            }
        });

        // Assert expectations
        mockGw01.assertIsSatisfied(2500);
        mockGw01Dlq.assertIsSatisfied(2500);

        // Check the Header
        Object msgId = mockGw01.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockGw01.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

    }

    /**
     * Tests The Happy Path Route with Null Header Sent.
     * 
     * @throws Exception
     */
    @Test
    public void ifNullHeaderThenIdIsJmsId() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGw01 = (MockEndpoint) camelContext.getEndpoint("mock:store_out");
        MockEndpoint mockGw01Dlq = (MockEndpoint) camelContext.getEndpoint("mock:store_dlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGw01.expectedMessageCount(1);
        mockGw01.expectedBodiesReceived("Hello from Camel");

        // Should be Nothing on the DLQ
        mockGw01Dlq.expectedMessageCount(0);

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gw01_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeader("WM_MSG_ID", null);
            }
        });

        // Assert expectations
        mockGw01.assertIsSatisfied(2500);
        mockGw01Dlq.assertIsSatisfied(2500);

        // Check the Header
        Object msgId = mockGw01.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockGw01.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

    }

    /**
     * Tests The Happy Path Route with Null Header Sent.
     * 
     * @throws Exception
     */
    @Test
    public void ifEmptyHeaderThenIdIsJmsId() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGw01 = (MockEndpoint) camelContext.getEndpoint("mock:store_out");
        MockEndpoint mockGw01Dlq = (MockEndpoint) camelContext.getEndpoint("mock:store_dlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGw01.expectedMessageCount(1);
        mockGw01.expectedBodiesReceived("Hello from Camel");

        // Should be Nothing on the DLQ
        mockGw01Dlq.expectedMessageCount(0);

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gw01_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeader("WM_MSG_ID", "");
            }
        });

        // Assert expectations
        mockGw01.assertIsSatisfied(2500);
        mockGw01Dlq.assertIsSatisfied(2500);

        // Check the Header
        Object msgId = mockGw01.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockGw01.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

    }

    /**
     * If Connect Exception Thrown then Letters Go to the DLQ.
     * 
     * @throws Exception
     */
    @Test
    public void ifConnectExceptionThenGoToDlq() throws Exception {

        // Get Mock Endpoint
        MockEndpoint mockGw01 = (MockEndpoint) camelContext.getEndpoint("mock:store_out");
        MockEndpoint mockGw01Dlq = (MockEndpoint) camelContext.getEndpoint("mock:store_dlq");

        // Configure Mock to Throw Exception
        String errorMsg = "Can Not Connect";
        ExceptionProcessor ep = new ExceptionProcessor(new ConnectException(errorMsg));
        mockGw01.whenAnyExchangeReceived(ep);

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        // Should be One on this Because it Fails
        mockGw01.expectedMessageCount(1);

        // Should be One on the DLQ
        mockGw01Dlq.expectedMessageCount(1);
        mockGw01Dlq.expectedBodiesReceived("Hello from Camel");

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gw01_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
            }
        });

        // Assert expectations
        mockGw01.assertIsSatisfied(2500);
        mockGw01Dlq.assertIsSatisfied(2500);

        // Check the Header
        Object wmErrorMsg = mockGw01Dlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals(errorMsg, wmErrorMsg);
    }

}
