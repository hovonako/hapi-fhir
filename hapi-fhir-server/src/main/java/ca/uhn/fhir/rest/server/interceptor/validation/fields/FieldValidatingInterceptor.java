package ca.uhn.fhir.rest.server.interceptor.validation.fields;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.fhirpath.IFhirPath;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.ConfigLoader;
import ca.uhn.fhir.rest.server.interceptor.ServerOperationInterceptorAdapter;
import ca.uhn.fhir.rest.server.interceptor.validation.address.IAddressValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class FieldValidatingInterceptor extends ServerOperationInterceptorAdapter {

	public enum ValidatorType {
		EMAIL;
	}

	private static final Logger ourLog = LoggerFactory.getLogger(FieldValidatingInterceptor.class);

	public static final String VALIDATION_DISABLED_HEADER = "CDR-Field-Validation-Disabled";

	private IAddressValidator myAddressValidator;

	private Map<String, String> myConfig;


	public FieldValidatingInterceptor() {
		super();

		ourLog.info("Starting FieldValidatingInterceptor {}", this);
		myConfig = ConfigLoader.loadJson("classpath:field-validation-rules.json", Map.class);
	}

	@Override
	public void resourcePreCreate(RequestDetails theRequest, IBaseResource theResource) {
		ourLog.debug("Validating address on for create {}, {}", theResource, theRequest);
		handleRequest(theRequest, theResource);
	}

	@Override
	public void resourcePreUpdate(RequestDetails theRequest, IBaseResource theOldResource, IBaseResource theNewResource) {
		ourLog.debug("Validating address on for update {}, {}, {}", theOldResource, theNewResource, theRequest);
		handleRequest(theRequest, theNewResource);
	}

	protected void handleRequest(RequestDetails theRequest, IBaseResource theResource) {
		if (!theRequest.getHeaders(VALIDATION_DISABLED_HEADER).isEmpty()) {
			ourLog.debug("Address validation is disabled for this request via header");
		}

		FhirContext ctx = theRequest.getFhirContext();
		IFhirPath fhirPath = ctx.newFhirPath();
		for (Map.Entry<String, String> e : myConfig.entrySet()) {
			IValidator validator = getValidator(e.getValue());

			List<IPrimitiveType> values = fhirPath.evaluate(theResource, e.getKey(), IPrimitiveType.class);
			for (IPrimitiveType value : values) {
				String valueAsString = value.getValueAsString();
				if (!validator.isValid(valueAsString)) {
					throw new IllegalArgumentException(String.format("Invalid resource %s", valueAsString));
				}
			}
		}
	}

	private IValidator getValidator(String theValue) {
		if (ValidatorType.EMAIL.name().equals(theValue)) {
			return new EmailValidator();
		}

		try {
			return (IValidator) Class.forName(theValue).getDeclaredConstructor().newInstance();
		} catch (Exception e) {
			throw new IllegalStateException(String.format("Unable to create validator for %s", theValue), e);
		}
	}

	public Map<String, String> getConfig() {
		return myConfig;
	}

	public void setConfig(Map<String, String> theConfig) {
		myConfig = theConfig;
	}
}