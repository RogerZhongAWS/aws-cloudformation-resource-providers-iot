package software.amazon.iot.softwarepackageversion;

import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.GetPackageVersionRequest;
import software.amazon.awssdk.services.iot.model.GetPackageVersionResponse;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.IotException;
import software.amazon.awssdk.services.iot.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.iot.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.iot.model.UpdatePackageVersionRequest;
import software.amazon.awssdk.services.iot.model.UpdatePackageVersionResponse;
import software.amazon.cloudformation.exceptions.CfnNotUpdatableException;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import com.amazonaws.iot.cfn.common.handler.Tagging;

import java.util.HashMap;
import java.util.Map;


/**
 * API Calls for UpdateHandler:
 * UpdateSoftwarePackageVersion: To update the properties of SoftwarePackageVersion
 * GetSoftwarePackageVersion: To retrieve ARN of the SoftwarePackageVersion to make Tag and UnTag API calls
 * ListTagsForResource: To retrieve old tags associated with SoftwarePackageVersion
 */
public class UpdateHandler extends BaseHandlerStd {

    private static final String OPERATION = "UpdateSoftwarePackageVersion";
    private static final String CALL_GRAPH = "AWS-IoT-SoftwarePackageVersion::Update";
    private static final String CALL_GRAPH_TAG = "AWS-IoT-SoftwarePackageVersion::TaggingUpdate";
    private Logger logger;
    private String clientToken;

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final ProxyClient<IotClient> proxyClient,
            final Logger logger) {

        this.logger = logger;
        this.clientToken = request.getClientRequestToken();

        ResourceModel prevModel = request.getPreviousResourceState() == null ?
                request.getDesiredResourceState() : request.getPreviousResourceState();
        ResourceModel newModel = request.getDesiredResourceState();
        final Map<String, String> stackTags = request.getDesiredResourceTags();
        final String awsAccountId = request.getAwsAccountId();

        logger.log(String.format("%s for accountId: %s",
                OPERATION, awsAccountId));

        Map<String, String> combinedTags = new HashMap<>();
        Map<String, String> modelTags = Translator.translateTagsToSdk(newModel.getTags());
        if (stackTags != null) {
            combinedTags.putAll(stackTags);
        }
        if (modelTags != null) {
            combinedTags.putAll(modelTags);
        }

        validatePropertiesAreUpdatable(newModel, prevModel);

        return ProgressEvent.progress(newModel, callbackContext)
                .then(progress ->
                        proxy.initiate(CALL_GRAPH, proxyClient, newModel, callbackContext)
                                .translateToServiceRequest(Translator::translateToUpdateRequest)
                                .makeServiceCall(this::updateResource)
                                .progress())
                .then(progress ->
                        proxy.initiate(CALL_GRAPH_TAG, proxyClient, newModel, callbackContext)
                                .translateToServiceRequest(model -> Translator.translateToListTagsRequestAfterUpdate(newModel, proxyClient, OPERATION, combinedTags))
                                .makeServiceCall(this::listResourceTags)
                                .progress())
                .then(progress -> new ReadHandler().handleRequest(proxy, request.toBuilder().desiredResourceState(newModel).build(), callbackContext, proxyClient, logger));
    }

    private void validatePropertiesAreUpdatable(ResourceModel newModel, ResourceModel prevModel) {
        if (!StringUtils.equals(newModel.getPackageName(), prevModel.getPackageName()) ||
                !StringUtils.equals(newModel.getVersionName(), prevModel.getVersionName())) {
            throwCfnNotUpdatableException("SoftwarePackageName");
        } else if (StringUtils.isNotEmpty(newModel.getPackageVersionArn()) && !StringUtils.equals(newModel.getPackageVersionArn(), prevModel.getPackageVersionArn())) {
            throwCfnNotUpdatableException("Arn");
        }
    }

    private void throwCfnNotUpdatableException(String propertyName) {
        throw new CfnNotUpdatableException(InvalidRequestException.builder()
                .message(String.format("Parameter '%s' is not updatable.", propertyName))
                .build());
    }

    private ListTagsForResourceResponse listResourceTags(
            ListTagsForResourceRequest listTagsForResourceRequest,
            ProxyClient<IotClient> proxyClient) {
        try {
            final ListTagsForResourceResponse listTagsForResourceResponse = proxyClient.injectCredentialsAndInvokeV2(
                    listTagsForResourceRequest, proxyClient.client()::listTagsForResource);
            logger.log(String.format("%s has been successfully updated with tags.",
                    ResourceModel.TYPE_NAME));
            return listTagsForResourceResponse;
        } catch (IotException e) {
            throw Translator.translateIotExceptionToHandlerException("Tagging operation", OPERATION, e);
        }
    }

    /**
     * Implement client invocation of the update request through the proxyClient, which is already initialised with
     * caller credentials, correct region and retry settings
     * @param updatePackageVersionRequest the aws service request to update a resource
     * @param proxyClient the aws service client to make the call
     * @return update resource response
     */
    private UpdatePackageVersionResponse updateResource(
            UpdatePackageVersionRequest updatePackageVersionRequest,
            ProxyClient<IotClient> proxyClient) {
        try {
            final UpdatePackageVersionResponse updatePackageVersionResponse = proxyClient.injectCredentialsAndInvokeV2(
                    updatePackageVersionRequest, proxyClient.client()::updatePackageVersion);
            logger.log(String.format("%s [%s, %s] has been successfully updated.",
                    ResourceModel.TYPE_NAME, updatePackageVersionRequest.packageName(), updatePackageVersionRequest.versionName()));
            return updatePackageVersionResponse;
        } catch (IotException e) {
            throw Translator.translateIotExceptionToHandlerException(updatePackageVersionRequest.packageName() + ":" + updatePackageVersionRequest.versionName(), OPERATION, e);
        }
    }
}
