package com.walmart.mqm.test.paxexam.gateway;

import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.logLevel;

import java.io.File;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.Map;
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
public class GatewayRouteTest extends CamelTestSupport {

    private ExecutorService executor = Executors.newCachedThreadPool();

    @Inject
    private FeaturesService featuresService;

    @Inject
    private BundleContext bundleContext;

    private CamelContext camelContext;

    // This should be the Name of the Camel Context you are Testing
    private static final String CAMEL_CONTEXT_NAME = "gatewayAmq2WmqBridge";

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
                                .classifier("features").versionAsInProject(), "camel-blueprint",
                        "camel-jpa", "camel-mvel", "camel-jdbc", "camel-cxf", "camel-test"),

                KarafDistributionOption.editConfigurationFilePut("etc/org.ops4j.pax.url.mvn.cfg",
                        "org.ops4j.pax.url.mvn.proxySupport", "true"),
                keepRuntimeFolder(),
                KarafDistributionOption.replaceConfigurationFile("etc/com.walmart.mqm.gateway.routes.cfg", new File(
                        "src/test/resources/com.walmart.mqm.gateway.routes.cfg")),

                mavenBundle().groupId("com.walmart.mqm").artifactId("gatewayBundle").versionAsInProject() };
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
        PaxExamTestUtil.assertBundleActive("com.walmart.mqm.gatewayBundle", bundleContext);

        // Assert Camel Context is Found
        String contextListCmd = PaxExamTestUtil.executeCommand("camel:context-list", executor, bundleContext);
        // System.out.println(contextListCmd);
        assertTrue("Doesn't contain desired camel-context", contextListCmd.contains(CAMEL_CONTEXT_NAME));

        // This code is useful for Debugging a Routes Tests
        /*
         * String routeListCmd = PaxExamTestUtil.executeCommand("camel:route-list", executor, bundleContext);
         * System.out.println(routeListCmd);
         * 
         * String routeInfoCmd = PaxExamTestUtil.executeCommand("camel:route-info gatewayAmqtoWwq", executor,
         * bundleContext); System.out.println(routeInfoCmd);
         */
    }

    /**
     * Tests The Route Where The Id is Null and the Queue is Null.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdNullAndQueueNullThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(0);

        // Should be on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(1);
        mockNoHomeOfficeDlq.expectedBodiesReceived("Hello from Camel");

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", null);
        headerMap.put("WM_HO_WMQ_QUEUE", null);

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

        // Check the Error Header
        Object err = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals("Header WM_HO_WMQ_QUEUE is not set. Unable to route to WMQ.", err);

        // Check the JMS Name
        String jmsName = (String) mockNoHomeOfficeDlq.getExchanges().get(0).getIn()
                .getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("DLQ"));

    }

    /**
     * Tests The Route Where The Id is Null and the Queue is Empty.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdNullAndQueueEmptyThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(0);

        // Should be on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(1);
        mockNoHomeOfficeDlq.expectedBodiesReceived("Hello from Camel");

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", null);
        headerMap.put("WM_HO_WMQ_QUEUE", "");

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

        // Check the Error Header
        Object err = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals("Header WM_HO_WMQ_QUEUE is not set. Unable to route to WMQ.", err);

        // Check the JMS Name
        String jmsName = (String) mockNoHomeOfficeDlq.getExchanges().get(0).getIn()
                .getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("DLQ"));

    }

    /**
     * Tests The Route Where The Id is Empty and the Queue is Null.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdEmptyAndQueueNullThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(0);

        // Should be on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(1);
        mockNoHomeOfficeDlq.expectedBodiesReceived("Hello from Camel");

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", "");
        headerMap.put("WM_HO_WMQ_QUEUE", null);

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

        // Check the Error Header
        Object err = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals("Header WM_HO_WMQ_QUEUE is not set. Unable to route to WMQ.", err);

        // Check the JMS Name
        String jmsName = (String) mockNoHomeOfficeDlq.getExchanges().get(0).getIn()
                .getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("DLQ"));

    }

    /**
     * Tests The Route Where The Id is Empty and the Queue is Empty.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdEmptyAndQueueEmptyThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(0);

        // Should be on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(1);
        mockNoHomeOfficeDlq.expectedBodiesReceived("Hello from Camel");

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", "");
        headerMap.put("WM_HO_WMQ_QUEUE", "");

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

        // Check the Error Header
        Object err = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals("Header WM_HO_WMQ_QUEUE is not set. Unable to route to WMQ.", err);

        // Check the JMS Name
        String jmsName = (String) mockNoHomeOfficeDlq.getExchanges().get(0).getIn()
                .getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("DLQ"));

    }

    /**
     * Tests The Route Where The Id is Null and the Queue is Set.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdNullAndQueueSetThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(1);
        mockGatewayOut.expectedBodiesReceived("Hello from Camel");

        // Should be nothing on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(0);

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", null);
        headerMap.put("WM_HO_WMQ_QUEUE", "TestQueue");

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockGatewayOut.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockGatewayOut.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

        // Check the Error Header
        Object err = mockGatewayOut.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertNull(err);

        // Check the JMS Name
        String jmsName = (String) mockGatewayOut.getExchanges().get(0).getIn().getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("TestQueue"));

    }

    /**
     * Tests The Route Where The Id is Empty and the Queue is Set.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdEmptyAndQueueSetThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(1);
        mockGatewayOut.expectedBodiesReceived("Hello from Camel");

        // Should be nothing on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(0);

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", "");
        headerMap.put("WM_HO_WMQ_QUEUE", "TestQueue");

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockGatewayOut.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Object jmsId = mockGatewayOut.getExchanges().get(0).getIn().getHeader("JMSMessageID");
        Assert.assertEquals(jmsId, msgId);

        // Check the Error Header
        Object err = mockGatewayOut.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertNull(err);

        // Check the JMS Name
        String jmsName = (String) mockGatewayOut.getExchanges().get(0).getIn().getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("TestQueue"));

    }

    /**
     * Tests The Route Where The Id is Null and the Queue is Set.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdSetAndQueueSetThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(1);
        mockGatewayOut.expectedBodiesReceived("Hello from Camel");

        // Should be nothing on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(0);

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", "12345");
        headerMap.put("WM_HO_WMQ_QUEUE", "TestQueue");

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockGatewayOut.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Assert.assertEquals("12345", msgId);

        // Check the Error Header
        Object err = mockGatewayOut.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertNull(err);

        // Check the JMS Name
        String jmsName = (String) mockGatewayOut.getExchanges().get(0).getIn().getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("TestQueue"));

    }

    /**
     * Tests The Route Where The Id is Set and the Queue is Empty.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdSetAndQueueEmptyThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(0);

        // Should be on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(1);
        mockNoHomeOfficeDlq.expectedBodiesReceived("Hello from Camel");

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", "12345");
        headerMap.put("WM_HO_WMQ_QUEUE", "");

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Assert.assertEquals("12345", msgId);

        // Check the Error Header
        Object err = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals("Header WM_HO_WMQ_QUEUE is not set. Unable to route to WMQ.", err);

        // Check the JMS Name
        String jmsName = (String) mockNoHomeOfficeDlq.getExchanges().get(0).getIn()
                .getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("DLQ"));

    }

    /**
     * Tests The Route Where The Id is Set and the Queue is Null.
     * 
     * @throws Exception
     */
    @Test
    public void ifIdSetAndQueueNullThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockNoHomeOfficeDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_noHomeOfficeDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(0);

        // Should be on the DLQ
        mockNoHomeOfficeDlq.expectedMessageCount(1);
        mockNoHomeOfficeDlq.expectedBodiesReceived("Hello from Camel");

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", "12345");
        headerMap.put("WM_HO_WMQ_QUEUE", null);

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockNoHomeOfficeDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Assert.assertEquals("12345", msgId);

        // Check the Error Header
        Object err = mockNoHomeOfficeDlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals("Header WM_HO_WMQ_QUEUE is not set. Unable to route to WMQ.", err);

        // Check the JMS Name
        String jmsName = (String) mockNoHomeOfficeDlq.getExchanges().get(0).getIn()
                .getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("DLQ"));

    }

    /**
     * Tests The Route Where The Id and Queue are Set but a ConnectException occurs.
     * 
     * @throws Exception
     */
    @Test
    public void ifConnectExceptionThenDlq() throws Exception {
        // Get Mock Endpoint
        MockEndpoint mockGatewayOut = (MockEndpoint) camelContext.getEndpoint("mock:gateway_out");
        MockEndpoint mockConnectExceptionDlq = (MockEndpoint) camelContext.getEndpoint("mock:gateway_connectExceptionDlq");

        // Setup Mock Expectations
        // This Must Be Done BEFORE You Send The Message.
        mockGatewayOut.expectedMessageCount(1);

        // Should be on the DLQ
        mockConnectExceptionDlq.expectedMessageCount(1);
        mockConnectExceptionDlq.expectedBodiesReceived("Hello from Camel");

        // Set Headers To Be Sent
        final Map<String, Object> headerMap = new HashMap<String, Object>();
        headerMap.put("WM_MSG_ID", "12345");
        headerMap.put("WM_HO_WMQ_QUEUE", "TesQueue");

        String errorMsg = "Can Not Connect";
        ExceptionProcessor ep = new ExceptionProcessor(new ConnectException(errorMsg));
        mockGatewayOut.whenAnyExchangeReceived(ep);

        // Send the Message Body
        ProducerTemplate template = camelContext.createProducerTemplate();
        template.start();

        template.send("direct:gateway_in", new Processor() {
            public void process(Exchange exchange) {
                Message in = exchange.getIn();
                in.setBody("Hello from Camel");
                in.setHeaders(headerMap);
            }
        });

        // Assert expectations
        mockGatewayOut.assertIsSatisfied(2500);
        mockConnectExceptionDlq.assertIsSatisfied(2500);

        // Check the Msg ID Header
        Object msgId = mockConnectExceptionDlq.getExchanges().get(0).getIn().getHeader("WM_MSG_ID");
        Assert.assertEquals("12345", msgId);

        // Check the Error Header
        Object err = mockConnectExceptionDlq.getExchanges().get(0).getIn().getHeader("WM_ERROR_MESSAGE");
        Assert.assertEquals(errorMsg, err);

        // Check the JMS Name
        String jmsName = (String) mockConnectExceptionDlq.getExchanges().get(0).getIn()
                .getHeader("CamelJmsDestinationName");
        Assert.assertTrue(jmsName.contains("DLQ"));

    }

}
