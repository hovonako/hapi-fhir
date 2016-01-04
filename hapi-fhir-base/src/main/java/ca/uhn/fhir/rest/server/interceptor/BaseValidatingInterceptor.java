package ca.uhn.fhir.rest.server.interceptor;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.text.StrLookup;
import org.apache.commons.lang3.text.StrSubstitutor;

import ca.uhn.fhir.rest.method.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.IValidatorModule;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;

/**
 * This interceptor intercepts each incoming request and if it contains a FHIR resource, validates that resource. The
 * interceptor may be configured to run any validator modules, and will then add headers to the response or fail the
 * request with an {@link UnprocessableEntityException HTTP 422 Unprocessable Entity}.
 */
abstract class BaseValidatingInterceptor<T> extends InterceptorAdapter {

	/**
	 * Default value:<br/>
	 * <code>
	 * ${row}:${col} ${severity} ${message} (${location})
	 * </code>
	 */
	public static final String DEFAULT_RESPONSE_HEADER_VALUE = "${row}:${col} ${severity} ${message} (${location})";

	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseValidatingInterceptor.class);

	private Integer myAddResponseHeaderOnSeverity = ResultSeverityEnum.INFORMATION.ordinal();
	private Integer myFailOnSeverity = ResultSeverityEnum.ERROR.ordinal();
	private String myResponseHeaderName = provideDefaultResponseHeaderName();
	private String myResponseHeaderValue = DEFAULT_RESPONSE_HEADER_VALUE;
	private String myResponseHeaderValueNoIssues = null;
	
	private List<IValidatorModule> myValidatorModules;

	private void addResponseHeader(RequestDetails theRequestDetails, SingleValidationMessage theNext) {
		// Perform any string substitutions from the message format
		StrLookup<?> lookup = new MyLookup(theNext);
		StrSubstitutor subs = new StrSubstitutor(lookup, "${", "}", '\\');

		// Log the header
		String headerValue = subs.replace(myResponseHeaderValue);
		ourLog.trace("Adding header to response: {}", headerValue);

		theRequestDetails.getResponse().addHeader(myResponseHeaderName, headerValue);
	}

	public BaseValidatingInterceptor addValidatorModule(IValidatorModule theModule) {
		Validate.notNull(theModule, "theModule must not be null");
		if (getValidatorModules() == null) {
			setValidatorModules(new ArrayList<IValidatorModule>());
		}
		getValidatorModules().add(theModule);
		return this;
	}

	abstract ValidationResult doValidate(FhirValidator theValidator, T theRequest);

	/**
	 * Fail the request by throwing an {@link UnprocessableEntityException} as a result of a validation failure.
	 * Subclasses may change this behaviour by providing alternate behaviour.
	 */
	protected void fail(RequestDetails theRequestDetails, ValidationResult theValidationResult) {
		throw new UnprocessableEntityException(theRequestDetails.getServer().getFhirContext(), theValidationResult.toOperationOutcome());
	}

	public List<IValidatorModule> getValidatorModules() {
		return myValidatorModules;
	}

	abstract String provideDefaultResponseHeaderName();

	/**
	 * Sets the minimum severity at which an issue detected by the validator will result in a header being added to the
	 * response. Default is {@link ResultSeverityEnum#INFORMATION}. Set to <code>null</code> to disable this behaviour.
	 * 
	 * @see #setResponseHeaderName(String)
	 * @see #setResponseHeaderValue(String)
	 */
	public void setAddResponseHeaderOnSeverity(ResultSeverityEnum theSeverity) {
		myAddResponseHeaderOnSeverity = theSeverity != null ? theSeverity.ordinal() : null;
	}

	/**
	 * Sets the minimum severity at which an issue detected by the validator will fail/reject the request. Default is
	 * {@link ResultSeverityEnum#ERROR}. Set to <code>null</code> to disable this behaviour.
	 */
	public void setFailOnSeverity(ResultSeverityEnum theSeverity) {
		myFailOnSeverity = theSeverity != null ? theSeverity.ordinal() : null;
	}

	/**
	 * Sets the name of the response header to add validation failures to
	 * 
	 * @see #setAddResponseHeaderOnSeverity(ResultSeverityEnum)
	 */
	protected void setResponseHeaderName(String theResponseHeaderName) {
		Validate.notBlank(theResponseHeaderName, "theResponseHeaderName must not be blank or null");
		myResponseHeaderName = theResponseHeaderName;
	}

	/**
	 * Sets the value to add to the response header with the name specified by {@link #setResponseHeaderName(String)}
	 * when validation produces a message of severity equal to or greater than
	 * {@link #setAddResponseHeaderOnSeverity(ResultSeverityEnum)}
	 * <p>
	 * This field allows the following substitutions:
	 * </p>
	 * <table>
	 * <tr>
	 * <td>Name</td>
	 * <td>Value</td>
	 * </tr>
	 * <tr>
	 * <td>${line}</td>
	 * <td>The line in the request</td>
	 * </tr>
	 * <tr>
	 * <td>${col}</td>
	 * <td>The column in the request</td>
	 * </tr>
	 * <tr>
	 * <td>${location}</td>
	 * <td>The location in the payload as a string (typically this will be a path)</td>
	 * </tr>
	 * <tr>
	 * <td>${severity}</td>
	 * <td>The severity of the issue</td>
	 * </tr>
	 * <tr>
	 * <td>${message}</td>
	 * <td>The validation message</td>
	 * </tr>
	 * </table>
	 * 
	 * @see #DEFAULT_RESPONSE_HEADER_VALUE
	 * @see #setAddResponseHeaderOnSeverity(ResultSeverityEnum)
	 */
	public void setResponseHeaderValue(String theResponseHeaderValue) {
		Validate.notBlank(theResponseHeaderValue, "theResponseHeaderValue must not be blank or null");
		myResponseHeaderValue = theResponseHeaderValue;
	}

	/**
	 * Sets the header value to add when no issues are found at or exceeding the
	 * threshold specified by {@link #setAddResponseHeaderOnSeverity(ResultSeverityEnum)} 
	 */
	public void setResponseHeaderValueNoIssues(String theResponseHeaderValueNoIssues) {
		myResponseHeaderValueNoIssues = theResponseHeaderValueNoIssues;
	}

	public void setValidatorModules(List<IValidatorModule> theValidatorModules) {
		myValidatorModules = theValidatorModules;
	}

	protected void validate(T theRequest, RequestDetails theRequestDetails) {
		FhirValidator validator = theRequestDetails.getServer().getFhirContext().newValidator();
		if (myValidatorModules != null) {
			for (IValidatorModule next : myValidatorModules) {
				validator.registerValidatorModule(next);
			}
		}

		ValidationResult validationResult = doValidate(validator, theRequest);
		
		if (myAddResponseHeaderOnSeverity != null) {
			boolean found = false;
			for (SingleValidationMessage next : validationResult.getMessages()) {
				if (next.getSeverity().ordinal() >= myAddResponseHeaderOnSeverity) {
					addResponseHeader(theRequestDetails, next);
					found = true;
				}
			}
			if (!found) {
				if (isNotBlank(myResponseHeaderValueNoIssues)) {
					theRequestDetails.getResponse().addHeader(myResponseHeaderName, myResponseHeaderValueNoIssues);
				}
			}
		}

		if (myFailOnSeverity != null) {
			for (SingleValidationMessage next : validationResult.getMessages()) {
				if (next.getSeverity().ordinal() >= myFailOnSeverity) {
					fail(theRequestDetails, validationResult);
					return;
				}
			}
		}
	}

	private static class MyLookup extends StrLookup<String> {

		private SingleValidationMessage myMessage;

		public MyLookup(SingleValidationMessage theMessage) {
			myMessage = theMessage;
		}

		@Override
		public String lookup(String theKey) {
			if ("line".equals(theKey)) {
				return toString(myMessage.getLocationLine());
			}
			if ("col".equals(theKey)) {
				return toString(myMessage.getLocationCol());
			}
			if ("message".equals(theKey)) {
				return toString(myMessage.getMessage());
			}
			if ("location".equals(theKey)) {
				return toString(myMessage.getLocationString());
			}
			if ("severity".equals(theKey)) {
				return myMessage.getSeverity() != null ? myMessage.getSeverity().name() : null;
			}

			return "";
		}

		private static String toString(Object theInt) {
			return theInt != null ? theInt.toString() : "";
		}

	}

}
