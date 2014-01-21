package com.walmart.mqm.test.paxexam.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.junit.Assert;
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

public class PaxExamTestUtil {

    private static Logger log = LoggerFactory.getLogger(PaxExamTestUtil.class);

    public static final Long COMMAND_TIMEOUT = 10000L;
    public static final Long DEFAULT_TIMEOUT = 20000L;
    public static final Long SERVICE_TIMEOUT = 30000L;

    private PaxExamTestUtil() {
        super();
    }

    @SuppressWarnings("rawtypes")
    public static String explode(Dictionary dictionary) {
        Enumeration keys = dictionary.keys();
        StringBuffer result = new StringBuffer();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            result.append(String.format("%s=%s", key, dictionary.get(key)));
            if (keys.hasMoreElements()) {
                result.append(", ");
            }
        }
        return result.toString();
    }

    @SuppressWarnings("rawtypes")
    public static Collection<ServiceReference> asCollection(ServiceReference[] references) {
        return references != null ? Arrays.asList(references) : Collections.<ServiceReference> emptyList();
    }

    public static <T> T getOsgiService(Class<T> type, long timeout, BundleContext bundleContext) {
        return getOsgiService(type, null, timeout, bundleContext);
    }

    public static <T> T getOsgiService(Class<T> type, BundleContext bundleContext) {
        return getOsgiService(type, null, SERVICE_TIMEOUT, bundleContext);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T> T getOsgiService(Class<T> type, String filter, long timeout, BundleContext bundleContext) {
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

                for (ServiceReference ref : PaxExamTestUtil.asCollection(bundleContext.getAllServiceReferences(null,
                        null))) {
                    System.err.println("ServiceReference: " + ref);
                }

                for (ServiceReference ref : PaxExamTestUtil.asCollection(bundleContext.getAllServiceReferences(null,
                        flt))) {
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

    public static void assertBundleActive(String bundleName, BundleContext bundleContext) {
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

    public static String executeCommand(final String command, ExecutorService executor, BundleContext bundleContext) {
        return executeCommand(command, COMMAND_TIMEOUT, false, executor, bundleContext);
    }

    public static String executeCommand(final String command, final Long timeout, final Boolean silent,
            ExecutorService executor, BundleContext bundleContext) {
        String response;
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        final PrintStream printStream = new PrintStream(byteArrayOutputStream);
        final CommandProcessor commandProcessor = getOsgiService(CommandProcessor.class, bundleContext);
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

}
