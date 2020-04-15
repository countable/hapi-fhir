package ca.uhn.fhir.jpa.empi.svc;

import ca.uhn.fhir.empi.api.IEmpiProperties;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.model.cross.ResourcePersistentId;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service
public class EmpiResourceDaoSvc {
	@Autowired
	DaoRegistry myDaoRegistry;
	@Autowired
    IEmpiProperties myEmpiConfig;

	private IFhirResourceDao myPatientDao;
	private IFhirResourceDao myPersonDao;
	private IFhirResourceDao myPractitionerDao;

	@PostConstruct
	public void postConstruct() {
		myPatientDao = myDaoRegistry.getResourceDao("Patient");
		myPersonDao = myDaoRegistry.getResourceDao("Person");
		myPractitionerDao = myDaoRegistry.getResourceDao("Practitioner");
	}

	public IBaseResource readPatient(IIdType theId) {
		return myPatientDao.read(theId);
	}

	public IBaseResource readPerson(IIdType theId) {
		return myPersonDao.read(theId);
	}

	public IBaseResource readPractitioner(IIdType theId) {
		return myPractitionerDao.read(theId);
	}

	public DaoMethodOutcome updatePerson(IBaseResource thePerson) {
		return myPersonDao.update(thePerson);
	}

	public IBaseResource readPersonByPid(ResourcePersistentId thePersonPid) {
		return myPersonDao.readByPid(thePersonPid);
	}

	public IBaseResource searchPersonByEid(String theEidFromResource) {
		SearchParameterMap map = new SearchParameterMap();
		map.setLoadSynchronous(true);
		map.add("identifier", new TokenParam(myEmpiConfig.getEmpiRules().getEnterpriseEIDSystem(), theEidFromResource));
		IBundleProvider search = myPersonDao.search(map);
		if (search.size() > 0) {
			return search.getResources(0, 1).get(0);
		} else {
			return null;
		}
	}
}
