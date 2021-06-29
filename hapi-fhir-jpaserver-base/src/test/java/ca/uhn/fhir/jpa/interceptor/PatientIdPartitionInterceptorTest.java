package ca.uhn.fhir.jpa.interceptor;

import ca.uhn.fhir.jpa.dao.r4.BaseJpaR4SystemTest;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class PatientIdPartitionInterceptorTest extends BaseJpaR4SystemTest {

	private PatientIdPartitionInterceptor mySvc;
	private ForceOffsetSearchModeInterceptor myForceOffsetSearchModeInterceptor;

	@BeforeEach
	public void before() {
		mySvc = new PatientIdPartitionInterceptor(myFhirCtx);
		myForceOffsetSearchModeInterceptor = new ForceOffsetSearchModeInterceptor();

		myInterceptorRegistry.registerInterceptor(mySvc);
		myInterceptorRegistry.registerInterceptor(myForceOffsetSearchModeInterceptor);

		myPartitionSettings.setPartitioningEnabled(true);
		myPartitionSettings.setUnnamedPartitionMode(true);
		myPartitionSettings.setDefaultPartitionId(-1);


	}

	@AfterEach
	public void after() {
		myInterceptorRegistry.unregisterInterceptor(mySvc);
		myInterceptorRegistry.unregisterInterceptor(myForceOffsetSearchModeInterceptor);

		myPartitionSettings.setPartitioningEnabled(false);
		myPartitionSettings.setUnnamedPartitionMode(new PartitionSettings().isUnnamedPartitionMode());
	}


	@Test
	public void testCreatePatient_ClientAssignedId() {
		createPatientA();

		runInTransaction(() -> {
			ResourceTable pt = myResourceTableDao.findAll().iterator().next();
			assertEquals("A", pt.getIdDt().getIdPart());
			assertEquals("A".hashCode(), pt.getPartitionId().getPartitionId());
		});
	}

	@Test
	public void testCreatePatient_NonClientAssignedId() {
		Patient patient = new Patient();
		patient.setActive(true);
		try {
			myPatientDao.create(patient);
			fail();
		} catch (PreconditionFailedException e) {
			assertEquals("Resource of type Patient has no values placing it in the Patient compartment", e.getMessage());
		}
	}

	@Test
	public void testCreateObservation_ValidMembershipInCompartment() {
		createPatientA();

		Observation obs = new Observation();
		obs.getSubject().setReference("Patient/A");
		Long id = myObservationDao.create(obs).getId().getIdPartAsLong();

		runInTransaction(() -> {
			ResourceTable observation = myResourceTableDao.findById(id).orElseThrow(() -> new IllegalArgumentException());
			assertEquals("Observation", observation.getResourceType());
			assertEquals("A".hashCode(), observation.getPartitionId().getPartitionId());
		});
	}

	/**
	 * Type is not in the patient compartment
	 */
	@Test
	public void testCreateOrganization_ValidMembershipInCompartment() {
		Organization org = new Organization();
		org.setName("Foo");
		Long id = myOrganizationDao.create(org).getId().getIdPartAsLong();

		runInTransaction(() -> {
			ResourceTable observation = myResourceTableDao.findById(id).orElseThrow(() -> new IllegalArgumentException());
			assertEquals("Organization", observation.getResourceType());
			assertEquals(-1, observation.getPartitionId().getPartitionId().intValue());
		});
	}

	@Test
	public void testReadPatient_Good() {
		createPatientA();

		myCaptureQueriesListener.clear();
		Patient patient = myPatientDao.read(new IdType("Patient/A"), mySrd);
		assertTrue(patient.getActive());
		myCaptureQueriesListener.logSelectQueries();
		assertEquals(3, myCaptureQueriesListener.getSelectQueries().size());
		assertThat(myCaptureQueriesListener.getSelectQueries().get(0).getSql(false,false), containsString("forcedid0_.PARTITION_ID in (?)"));
		assertThat(myCaptureQueriesListener.getSelectQueries().get(1).getSql(false,false), containsString("where resourceta0_.PARTITION_ID=? and resourceta0_.RES_ID=?"));
	}

	@Test
	public void testSearchPatient_Good() {
		createPatientA();

		myCaptureQueriesListener.clear();
		IBundleProvider outcome = myPatientDao.search(SearchParameterMap.newSynchronous("_id", new TokenParam("A")), mySrd);
		assertEquals(1, outcome.size());
		myCaptureQueriesListener.logSelectQueries();
		assertEquals(3, myCaptureQueriesListener.getSelectQueries().size());
		assertThat(myCaptureQueriesListener.getSelectQueries().get(0).getSql(false,false), containsString("forcedid0_.PARTITION_ID in (?)"));
		assertThat(myCaptureQueriesListener.getSelectQueries().get(1).getSql(false,false), containsString("t0.PARTITION_ID = ?"));
	}

	@Test
	public void testSearchObservation_Good() {
		createPatientA();
		createObservationB();

		myCaptureQueriesListener.clear();
		IBundleProvider outcome = myObservationDao.search(SearchParameterMap.newSynchronous("subject", new ReferenceParam("Patient/A")), mySrd);
		assertEquals(1, outcome.size());
		myCaptureQueriesListener.logSelectQueries();
		assertEquals(2, myCaptureQueriesListener.getSelectQueries().size());
		assertThat(myCaptureQueriesListener.getSelectQueries().get(0).getSql(false,false), containsString("SELECT t0.SRC_RESOURCE_ID FROM HFJ_RES_LINK t0 WHERE ((t0.PARTITION_ID = ?)"));
	}

	@Test
	public void testSearchObservation_NoCompartmentMembership() {
		createPatientA();
		createObservationB();

		myCaptureQueriesListener.clear();
		try {
			myObservationDao.search(SearchParameterMap.newSynchronous(), mySrd);
		} catch (InvalidRequestException e) {
			assertEquals("This server is not able to handle this request of type SEARCH_TYPE", e.getMessage());
		}
	}

	/**
	 * Type is not in the patient compartment
	 */
	@Test
	public void testSearchOrganization_Good() {
		createOrganizationC();

		myCaptureQueriesListener.clear();
		IBundleProvider outcome = myOrganizationDao.search(SearchParameterMap.newSynchronous(), mySrd);
		assertEquals(1, outcome.size());
		myCaptureQueriesListener.logSelectQueries();
		assertEquals(2, myCaptureQueriesListener.getSelectQueries().size());
		assertThat(myCaptureQueriesListener.getSelectQueries().get(0).getSql(false,false), containsString("t0.PARTITION_ID = ?"));
	}

	private void createOrganizationC() {
		Organization org = new Organization();
		org.setId("C");
		org.setName("Foo");
		myOrganizationDao.update(org);
	}

	private void createObservationB() {
		Observation obs = new Observation();
		obs.setId("B");
		obs.getSubject().setReference("Patient/A");
		myObservationDao.update(obs);
	}

	private void createPatientA() {
		Patient patient = new Patient();
		patient.setId("Patient/A");
		patient.setActive(true);
		myPatientDao.update(patient);
	}

}
