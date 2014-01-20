package com.walmart.mqm.test.paxexam.store;

import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.streamBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.logLevel;
import static org.ops4j.pax.tinybundles.core.TinyBundles.bundle;

import java.io.File;

import javax.inject.Inject;
import org.ops4j.pax.exam.Option;

import org.apache.camel.test.junit4.CamelTestSupport;
import org.apache.karaf.features.FeaturesService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.karaf.options.KarafDistributionOption;
import org.ops4j.pax.exam.karaf.options.LogLevelOption.LogLevel;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(PaxExam.class)
public class StoreRouteTest extends CamelTestSupport {

    protected transient Logger log = LoggerFactory.getLogger(getClass());

    @Inject
    protected FeaturesService featuresService;

    @Inject
    protected BundleContext bundleContext;

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
                        "org.ops4j.pax.url.mvn.proxySupport", "true"), keepRuntimeFolder(),

                mavenBundle().groupId("de.nierbeck.camel.exam.demo").artifactId("entities").versionAsInProject() };
    }

    @Test
    public void test() throws Exception {
        assertTrue(featuresService.isInstalled(featuresService.getFeature("camel-core")));
        assertTrue(featuresService.isInstalled(featuresService.getFeature("camel-blueprint")));
    }

}
