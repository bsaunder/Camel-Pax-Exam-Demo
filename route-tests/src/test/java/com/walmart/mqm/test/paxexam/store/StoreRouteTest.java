package com.walmart.mqm.test.paxexam.store;

import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.logLevel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.walmart.mqm.test.paxexam.util.TestUtility;

@RunWith(PaxExam.class)
public class StoreRouteTest extends CamelTestSupport {

    // TODO Extract These
    static final Long COMMAND_TIMEOUT = 10000L;
    static final Long DEFAULT_TIMEOUT = 20000L;
    static final Long SERVICE_TIMEOUT = 30000L;
    ExecutorService executor = Executors.newCachedThreadPool();

    protected transient Logger log = LoggerFactory.getLogger(this.getClass());

    @Inject
    protected FeaturesService featuresService;

    @Inject
    protected BundleContext bundleContext;

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

    // TODO Extract
    protected <T> T getOsgiService(Class<T> type, long timeout) {
        return getOsgiService(type, null, timeout);
    }

    // TODO Extract
    protected <T> T getOsgiService(Class<T> type) {
        return getOsgiService(type, null, SERVICE_TIMEOUT);
    }

    // TODO Extract
    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected <T> T getOsgiService(Class<T> type, String filter, long timeout) {
        ServiceTracker tracker = null;
        try {
            String flt;
            if (filter != null) {
                if (filter.startsWith("(")) {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")" + filter + ")";
                } else {
                    flt = "(&(" + Constants.OBJECTCLASS + "=" + type.getName() + ")(" + filter + "))";
                }
            } else {
                flt = "(" + Constants.OBJECTCLASS + "=" + type.getName() + ")";
            }
            Filter osgiFilter = FrameworkUtil.createFilter(flt);
            tracker = new ServiceTracker(bundleContext, osgiFilter, null);
            tracker.open(true);
            // Note that the tracker is not closed to keep the reference
            // This is buggy, as the service reference may change i think
            Object svc = type.cast(tracker.waitForService(timeout));
            if (svc == null) {

                for (ServiceReference ref : TestUtility.asCollection(bundleContext.getAllServiceReferences(null, null))) {
                    System.err.println("ServiceReference: " + ref);
                }

                for (ServiceReference ref : TestUtility.asCollection(bundleContext.getAllServiceReferences(null, flt))) {
                    System.err.println("Filtered ServiceReference: " + ref);
                }

                throw new RuntimeException("Gave up waiting for service " + flt);
            }
            return type.cast(svc);
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException("Invalid filter", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO Extract This
    protected void assertBundleActive(String bundleName) {
        log.info("Asserting {} is active", bundleName);
        Bundle[] bundles = bundleContext.getBundles();
        boolean found = false;
        boolean active = false;

        for (Bundle bundle : bundles) {
            if (bundle.getSymbolicName().equals(bundleName)) {
                found = true;
                if (bundle.getState() == Bundle.ACTIVE) {
                    log.info("  ACTIVE");
                    active = true;
                } else {
                    log.info("  NOT ACTIVE");
                }
                break;
            }
        }
        Assert.assertTrue(bundleName + " not found in container", found);
        Assert.assertTrue(bundleName + " not active", active);
    }

    // TODO Extract This
    protected String executeCommand(final String command) {
        return executeCommand(command, COMMAND_TIMEOUT, false);
    }

    // TODO Extract This
    protected String executeCommand(final String command, final Long timeout, final Boolean silent) {
        String response;
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final PrintStream printStream = new PrintStream(byteArrayOutputStream);
        final CommandProcessor commandProcessor = getOsgiService(CommandProcessor.class);
        final CommandSession commandSession = commandProcessor.createSession(System.in, printStream, System.err);
        FutureTask<String> commandFuture = new FutureTask<String>(new Callable<String>() {
            public String call() {
                try {
                    if (!silent) {
                        System.err.println(command);
                    }
                    commandSession.execute(command);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
                printStream.flush();
                return byteArrayOutputStream.toString();
            }
        });

        try {
            executor.submit(commandFuture);
            response = commandFuture.get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            response = "SHELL COMMAND TIMED OUT: ";
        }

        return response;
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
        camelContext = getOsgiService(CamelContext.class, "(camel.context.name=" + CAMEL_CONTEXT_NAME + ")", 10000);
        assertNotNull(camelContext);
    }

    @Before
    public void testSetup() throws Exception {
        // Assert Camel Features Installed
        assertTrue(featuresService.isInstalled(featuresService.getFeature("camel-core")));
        assertTrue(featuresService.isInstalled(featuresService.getFeature("camel-blueprint")));

        // Assert Bundle is Activated
        assertBundleActive("com.walmart.mqm.storeBundle");

        // Assert Camel Context is Found
        String contextListCmd = executeCommand("camel:context-list");
        System.out.println(contextListCmd);
        assertTrue("Doesn't contain desired camel-context", contextListCmd.contains(CAMEL_CONTEXT_NAME));
        
        // This code is useful for Debugging a Routes Tests
        /*String routeListCmd = executeCommand("camel:route-list");
        System.out.println(routeListCmd);
        
        String routeInfoCmd = executeCommand("camel:route-info storeToGW01");
        System.out.println(routeInfoCmd);*/
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
        //this.template.sendBody("direct:gw01_in", "Hello from Camel");
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

}
