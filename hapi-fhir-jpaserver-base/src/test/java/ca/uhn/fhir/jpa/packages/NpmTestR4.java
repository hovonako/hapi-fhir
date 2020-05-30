package ca.uhn.fhir.jpa.packages;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.jpa.dao.data.INpmPackageDao;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionDao;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionResourceDao;
import ca.uhn.fhir.jpa.dao.r4.BaseJpaR4Test;
import ca.uhn.fhir.jpa.model.entity.NpmPackageEntity;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionEntity;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionResourceEntity;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.test.utilities.JettyUtil;
import ca.uhn.fhir.test.utilities.ProxyUtil;
import ca.uhn.fhir.util.JsonUtil;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ImplementationGuide;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.utilities.cache.NpmPackage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class NpmTestR4 extends BaseJpaR4Test {

	private static final Logger ourLog = LoggerFactory.getLogger(NpmTestR4.class);
	private final Map<String, byte[]> myResponses = new HashMap<>();
	@Autowired
	public INpmInstallerSvc igInstaller;
	@Autowired
	private IHapiPackageCacheManager myPackageCacheManager;
	@Autowired
	private NpmJpaValidationSupport myNpmJpaValidationSupport;
	private Server myServer;
	@Autowired
	private INpmPackageDao myPackageDao;
	@Autowired
	private INpmPackageVersionDao myPackageVersionDao;
	@Autowired
	private INpmPackageVersionResourceDao myPackageVersionResourceDao;

	@Before
	public void before() throws Exception {
		JpaPackageCache jpaPackageCache = ProxyUtil.getSingletonTarget(myPackageCacheManager, JpaPackageCache.class);

		myServer = new Server(0);
		ServletHandler proxyHandler = new ServletHandler();
		FakeNpmServlet fakeNpmServlet = new FakeNpmServlet();
		ServletHolder servletHolder = new ServletHolder(fakeNpmServlet);
		proxyHandler.addServletWithMapping(servletHolder, "/*");
		myServer.setHandler(proxyHandler);
		myServer.start();

		int port = JettyUtil.getPortForStartedServer(myServer);
		jpaPackageCache.getPackageServers().clear();
		jpaPackageCache.addPackageServer("http://localhost:" + port);

		myResponses.clear();
	}

	@After
	public void after() throws Exception {
		JettyUtil.closeServer(myServer);
	}

	@Test
	public void testCacheDstu3Package() throws Exception {
		byte[] bytes = loadClasspathBytes("/packages/NHSD.Assets.STU3.tar.gz");
		myResponses.put("/NHSD.Assets.STU3/1.2.0", bytes);

		NpmInstallationSpec spec = new NpmInstallationSpec().setPackageId("NHSD.Assets.STU3").setPackageVersion("1.2.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		// Be sure no further communication with the server
		JettyUtil.closeServer(myServer);

		// Make sure we can fetch the package by ID and Version
		NpmPackage pkg = myPackageCacheManager.loadPackage("NHSD.Assets.STU3", "1.2.0");
		assertEquals("STU3 Assets from our Github account and Care Connect Profiles have been added from Github https://github.com/nhsconnect/CareConnect-profiles/tree/develop", pkg.description());

		// Make sure we can fetch the package by ID
		pkg = myPackageCacheManager.loadPackage("NHSD.Assets.STU3", null);
		assertEquals("1.2.0", pkg.version());
		assertEquals("STU3 Assets from our Github account and Care Connect Profiles have been added from Github https://github.com/nhsconnect/CareConnect-profiles/tree/develop", pkg.description());

		// Fetch resource by URL
		FhirContext fhirContext = FhirContext.forDstu3();
		runInTransaction(() -> {
			IBaseResource asset = myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.DSTU3, "https://fhir.hl7.org.uk/STU3/StructureDefinition/CareConnect-ACVPU-Observation-1");
			assertThat(fhirContext.newJsonParser().encodeResourceToString(asset), containsString("\"url\":\"https://fhir.hl7.org.uk/STU3/StructureDefinition/CareConnect-ACVPU-Observation-1\",\"version\":\"1.0.0\""));
		});

		// Fetch resource by URL with version
		runInTransaction(() -> {
			IBaseResource asset = myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.DSTU3, "https://fhir.hl7.org.uk/STU3/StructureDefinition/CareConnect-ACVPU-Observation-1|1.0.0");
			assertThat(fhirContext.newJsonParser().encodeResourceToString(asset), containsString("\"url\":\"https://fhir.hl7.org.uk/STU3/StructureDefinition/CareConnect-ACVPU-Observation-1\",\"version\":\"1.0.0\""));
		});

		// This was saved but is the wrong version of FHIR for this server
		assertNull(myNpmJpaValidationSupport.fetchStructureDefinition("http://fhir.de/StructureDefinition/condition-de-basis/0.2"));
	}

	@Test
	public void testInstallR4Package() throws Exception {
		myDaoConfig.setAllowExternalReferences(true);

		byte[] bytes = loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.12.0.tgz");
		myResponses.put("/hl7.fhir.uv.shorthand/0.12.0", bytes);

		NpmInstallationSpec spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.12.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_AND_INSTALL);
		igInstaller.install(spec);

		// Be sure no further communication with the server
		JettyUtil.closeServer(myServer);

		// Make sure we can fetch the package by ID and Version
		NpmPackage pkg = myPackageCacheManager.loadPackage("hl7.fhir.uv.shorthand", "0.12.0");
		assertEquals("Describes FHIR Shorthand (FSH), a domain-specific language (DSL) for defining the content of FHIR Implementation Guides (IG). (built Wed, Apr 1, 2020 17:24+0000+00:00)", pkg.description());

		// Make sure we can fetch the package by ID
		pkg = myPackageCacheManager.loadPackage("hl7.fhir.uv.shorthand", null);
		assertEquals("0.12.0", pkg.version());
		assertEquals("Describes FHIR Shorthand (FSH), a domain-specific language (DSL) for defining the content of FHIR Implementation Guides (IG). (built Wed, Apr 1, 2020 17:24+0000+00:00)", pkg.description());

		// Make sure DB rows were saved
		runInTransaction(() -> {
			NpmPackageEntity pkgEntity = myPackageDao.findByPackageId("hl7.fhir.uv.shorthand").orElseThrow(() -> new IllegalArgumentException());
			assertEquals("hl7.fhir.uv.shorthand", pkgEntity.getPackageId());

			NpmPackageVersionEntity versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.12.0").orElseThrow(() -> new IllegalArgumentException());
			assertEquals("hl7.fhir.uv.shorthand", versionEntity.getPackageId());
			assertEquals("0.12.0", versionEntity.getVersionId());
			assertEquals(3001, versionEntity.getPackageSizeBytes());
			assertEquals(true, versionEntity.isCurrentVersion());
			assertEquals("hl7.fhir.uv.shorthand", versionEntity.getName());
			assertEquals("4.0.1", versionEntity.getFhirVersionId());
			assertEquals(FhirVersionEnum.R4, versionEntity.getFhirVersion());

			NpmPackageVersionResourceEntity resource = myPackageVersionResourceDao.findCurrentVersionByCanonicalUrl(Pageable.unpaged(), FhirVersionEnum.R4, "http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand").getContent().get(0);
			assertEquals("http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand", resource.getCanonicalUrl());
			assertEquals("0.12.0", resource.getCanonicalVersion());
			assertEquals("ImplementationGuide-hl7.fhir.uv.shorthand.json", resource.getFilename());
			assertEquals("4.0.1", resource.getFhirVersionId());
			assertEquals(FhirVersionEnum.R4, resource.getFhirVersion());
			assertEquals(6155, resource.getResSizeBytes());
		});

		// Fetch resource by URL
		runInTransaction(() -> {
			IBaseResource asset = myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.R4, "http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand");
			assertThat(myFhirCtx.newJsonParser().encodeResourceToString(asset), containsString("\"url\":\"http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand\",\"version\":\"0.12.0\""));
		});

		// Fetch resource by URL with version
		runInTransaction(() -> {
			IBaseResource asset = myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.R4, "http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand|0.12.0");
			assertThat(myFhirCtx.newJsonParser().encodeResourceToString(asset), containsString("\"url\":\"http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand\",\"version\":\"0.12.0\""));
		});

		// Search for the installed resource
		runInTransaction(() -> {
			SearchParameterMap map = SearchParameterMap.newSynchronous();
			map.add(StructureDefinition.SP_URL, new UriParam("http://hl7.org/fhir/uv/shorthand/CodeSystem/shorthand-code-system"));
			IBundleProvider outcome = myCodeSystemDao.search(map);
			assertEquals(1, outcome.sizeOrThrowNpe());
		});
	}


	@Test
	public void testLoadPackageMetadata() throws Exception {
		myDaoConfig.setAllowExternalReferences(true);

		myResponses.put("/hl7.fhir.uv.shorthand/0.12.0", loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.12.0.tgz"));
		myResponses.put("/hl7.fhir.uv.shorthand/0.11.1", loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.11.1.tgz"));

		NpmInstallationSpec spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.12.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);
		spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.11.1").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		runInTransaction(() -> {
			NpmPackageMetadataJson metadata = myPackageCacheManager.loadPackageMetadata("hl7.fhir.uv.shorthand");
			try {
				ourLog.info(JsonUtil.serialize(metadata));

				assertEquals("0.12.0", metadata.getDistTags().getLatest());

				assertThat(metadata.getVersions().keySet(), contains("0.12.0", "0.11.1"));

			} catch (IOException e) {
				throw new InternalErrorException(e);
			}
		});

	}


	@Test
	public void testInstallNewerPackageUpdatesLatestVersionFlag() throws Exception {
		myDaoConfig.setAllowExternalReferences(true);

		byte[] contents0111 = loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.11.1.tgz");
		byte[] contents0120 = loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.12.0.tgz");
		myResponses.put("/hl7.fhir.uv.shorthand/0.11.1", contents0111);
		myResponses.put("/hl7.fhir.uv.shorthand/0.12.0", contents0120);

		// Install older version
		NpmInstallationSpec spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.11.1").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		// Older version is current
		runInTransaction(() -> {
			NpmPackageVersionEntity versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.11.1").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(true, versionEntity.isCurrentVersion());
		});

		// Fetching a resource should return the older version
		runInTransaction(() -> {
			ImplementationGuide ig = (ImplementationGuide) myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.R4, "http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand");
			assertEquals("0.11.1", ig.getVersion());
		});

		// Now install newer version
		spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.12.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		// Newer version is current
		runInTransaction(() -> {
			NpmPackageVersionEntity versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.11.1").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(false, versionEntity.isCurrentVersion());

			versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.12.0").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(true, versionEntity.isCurrentVersion());
		});

		// Fetching a resource should return the newer version
		runInTransaction(() -> {
			ImplementationGuide ig = (ImplementationGuide) myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.R4, "http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand");
			assertEquals("0.12.0", ig.getVersion());
		});
	}

	@Test
	public void testInstallOlderPackageDoesntUpdatLatestVersionFlag() throws Exception {
		myDaoConfig.setAllowExternalReferences(true);

		myResponses.put("/hl7.fhir.uv.shorthand/0.12.0", loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.12.0.tgz"));
		myResponses.put("/hl7.fhir.uv.shorthand/0.11.1", loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.11.1.tgz"));

		// Install newer version
		NpmInstallationSpec spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.12.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		runInTransaction(() -> {
			NpmPackageVersionEntity versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.12.0").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(true, versionEntity.isCurrentVersion());
		});

		// Fetching a resource should return the older version
		runInTransaction(() -> {
			ImplementationGuide ig = (ImplementationGuide) myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.R4, "http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand");
			assertEquals("0.12.0", ig.getVersion());
		});

		// Install older version
		spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.11.1").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		// Newer version is still current
		runInTransaction(() -> {
			NpmPackageVersionEntity versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.11.1").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(false, versionEntity.isCurrentVersion());

			versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.12.0").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(true, versionEntity.isCurrentVersion());
		});

		// Fetching a resource should return the newer version
		runInTransaction(() -> {
			ImplementationGuide ig = (ImplementationGuide) myPackageCacheManager.loadPackageAssetByUrl(FhirVersionEnum.R4, "http://hl7.org/fhir/uv/shorthand/ImplementationGuide/hl7.fhir.uv.shorthand");
			assertEquals("0.12.0", ig.getVersion());
		});
	}

	@Test
	public void testInstallAlreadyExistingIsIgnored() throws Exception {
		myDaoConfig.setAllowExternalReferences(true);

		myResponses.put("/hl7.fhir.uv.shorthand/0.12.0", loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.12.0.tgz"));

		// Install
		NpmInstallationSpec spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.12.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		runInTransaction(() -> {
			NpmPackageVersionEntity versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.12.0").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(true, versionEntity.isCurrentVersion());
		});

		// Install same again
		spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.12.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY);
		igInstaller.install(spec);

		runInTransaction(() -> {
			NpmPackageVersionEntity versionEntity = myPackageVersionDao.findByPackageIdAndVersion("hl7.fhir.uv.shorthand", "0.12.0").orElseThrow(() -> new IllegalArgumentException());
			assertEquals(true, versionEntity.isCurrentVersion());
		});

	}

	@Test
	public void testLoadContents() throws IOException {
		byte[] contents0111 = loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.11.1.tgz");
		byte[] contents0120 = loadClasspathBytes("/packages/hl7.fhir.uv.shorthand-0.12.0.tgz");

		NpmInstallationSpec spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.11.1").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY).setContents(contents0111);
		igInstaller.install(spec);
		spec = new NpmInstallationSpec().setPackageId("hl7.fhir.uv.shorthand").setPackageVersion("0.12.0").setInstallMode(NpmInstallationSpec.InstallModeEnum.CACHE_ONLY).setContents(contents0120);
		igInstaller.install(spec);


		assertArrayEquals(contents0111, myPackageCacheManager.loadPackageContents("hl7.fhir.uv.shorthand", "0.11.1"));
		assertArrayEquals(contents0120, myPackageCacheManager.loadPackageContents("hl7.fhir.uv.shorthand", "0.12.0"));
		assertArrayEquals(contents0120, myPackageCacheManager.loadPackageContents("hl7.fhir.uv.shorthand", "latest"));
		assertEquals(null, myPackageCacheManager.loadPackageContents("hl7.fhir.uv.shorthand", "1.2.3"));
		assertEquals(null, myPackageCacheManager.loadPackageContents("foo", "1.2.3"));
	}

	private class FakeNpmServlet extends HttpServlet {

		@Override
		protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			String requestUrl = req.getRequestURI();
			if (myResponses.containsKey(requestUrl)) {
				ourLog.info("Responding to request: {}", requestUrl);

				resp.setStatus(200);
				resp.setHeader(Constants.HEADER_CONTENT_TYPE, "application/gzip");
				resp.getOutputStream().write(myResponses.get(requestUrl));
				resp.getOutputStream().close();
			} else {
				ourLog.warn("Unknown request: {}", requestUrl);

				resp.sendError(404);
			}

		}
	}
}
