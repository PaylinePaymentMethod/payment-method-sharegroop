package com.payline.payment.sharegroop.utils.http;

import com.payline.payment.sharegroop.bean.SharegroopAPICallResponse;
import com.payline.payment.sharegroop.bean.configuration.RequestConfiguration;
import com.payline.payment.sharegroop.bean.payment.Order;
import com.payline.payment.sharegroop.exception.InvalidDataException;
import com.payline.payment.sharegroop.exception.PluginException;
import com.payline.payment.sharegroop.service.JsonService;
import com.payline.payment.sharegroop.utils.Constants;
import com.payline.payment.sharegroop.utils.PluginUtils;
import com.payline.payment.sharegroop.utils.properties.ConfigProperties;
import com.payline.pmapi.bean.common.FailureCause;
import com.payline.pmapi.logger.LogManager;
import org.apache.http.HttpHeaders;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;


public class SharegroopHttpClient {

    private static final Logger LOGGER = LogManager.getLogger(SharegroopHttpClient.class);
    private final JsonService jsonService = JsonService.getInstance();

    //Headers
    private static final String CONTENT_TYPE_VALUE = "application/json";

    // Paths
    private static final String PATH_VERSION = "v1";
    private static final String PATH_ORDER = "orders";
    private static final String REFUND = "refund";
    private static final String CANCEL = "cancel";

    // Exceptions messages
    private static final String SERVICE_URL_ERROR = "Service URL is invalid";
    private static final String MISSING_ORDER_ID = "Missing an order Id";

    /**
     * The number of time the client must retry to send the request if it doesn't obtain a response.
     */
    private int retries;

    private HttpClient client;

    // --- Singleton Holder pattern + initialization BEGIN
    /**
     * ------------------------------------------------------------------------------------------------------------------
     */
    SharegroopHttpClient() {
            int connectionRequestTimeout;
            int connectTimeout;
            int socketTimeout;
            try {
                // request config timeouts (in seconds)
                ConfigProperties config = ConfigProperties.getInstance();
                connectionRequestTimeout = Integer.parseInt(config.get("http.connectionRequestTimeout"));
                connectTimeout = Integer.parseInt(config.get("http.connectTimeout"));
                socketTimeout = Integer.parseInt(config.get("http.socketTimeout"));

                // retries
                this.retries = Integer.parseInt(config.get("http.retries"));
            } catch (NumberFormatException e) {
                throw new PluginException("plugin error: http.* properties must be integers", e);
            }

            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(connectionRequestTimeout * 1000)
                    .setConnectTimeout(connectTimeout * 1000)
                    .setSocketTimeout(socketTimeout * 1000)
                    .build();

            // instantiate Apache HTTP client
            this.client = HttpClientBuilder.create()
                    .useSystemProperties()
                    .setDefaultRequestConfig(requestConfig)
                    .setSSLSocketFactory(new SSLConnectionSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), SSLConnectionSocketFactory.getDefaultHostnameVerifier()))
                    .build();

    }
    /**
     * ------------------------------------------------------------------------------------------------------------------
     */
    private static class Holder {
        private static final SharegroopHttpClient instance = new SharegroopHttpClient();
    }

    /**
     * ------------------------------------------------------------------------------------------------------------------
     */
    public static SharegroopHttpClient getInstance() {
        return Holder.instance;
    }
    // --- Singleton Holder pattern + initialization END

    /**
     * ------------------------------------------------------------------------------------------------------------------
     */
    private String createPath(String... path) {
        StringBuilder sb = new StringBuilder("/");
        if (path != null && path.length > 0) {
            for (String aPath : path) {
                sb.append(aPath).append("/");
            }
        }
        return sb.toString();
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Send the request, with a retry system in case the client does not obtain a proper response from the server.
     *
     * @param httpRequest The request to send.
     * @return The response converted as a {@link StringResponse}.
     * @throws PluginException If an error repeatedly occurs and no proper response is obtained.
     */
    StringResponse execute(HttpRequestBase httpRequest) {
        StringResponse strResponse = null;
        int attempts = 1;

        while (strResponse == null && attempts <= this.retries) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Start call to partner API (request : {}) (attempt : {}) ", PluginUtils.requestToString(httpRequest), attempts);
            } else {
                LOGGER.info("Start call to partner API [{} {}] (attempt {})", httpRequest.getMethod(), httpRequest.getURI(), attempts);
            }
            try (CloseableHttpResponse httpResponse = (CloseableHttpResponse) this.client.execute(httpRequest)) {
                strResponse = StringResponse.fromHttpResponse(httpResponse);
            } catch (IOException e) {
                LOGGER.error("An error occurred during the HTTP call :", e);
                strResponse = null;
            } finally {
                attempts++;
            }
        }

        if (strResponse == null) {
            throw new PluginException("Failed to contact the partner API", FailureCause.COMMUNICATION_ERROR);
        }
        LOGGER.info("Response obtained from partner API [{} {}]", strResponse.getStatusCode(), strResponse.getStatusMessage());
        return strResponse;
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Verify if API url are present
     *
     * @param requestConfiguration
     */
    private void verifyPartnerConfigurationURL(RequestConfiguration requestConfiguration) {
        if (requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.SHAREGROOP_URL)== null) {
            throw new InvalidDataException("Missing API url from partner configuration (sentitive properties)");
        }

        if (requestConfiguration.getContractConfiguration().getProperty(Constants.ContractConfigurationKeys.PRIVATE_KEY) == null ||
                requestConfiguration.getContractConfiguration().getProperty(Constants.ContractConfigurationKeys.PRIVATE_KEY).getValue() == null) {
            throw new InvalidDataException("Missing client private key from partner configuration (sentitive properties)");
        }
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Verify the transaction status after a buyer action
     * @param requestConfiguration
     * @param createdOrderId
     * @return
     */
    public SharegroopAPICallResponse verifyOrder(RequestConfiguration requestConfiguration, String createdOrderId){
        // Check if API url are present
        verifyPartnerConfigurationURL(requestConfiguration);

        // Check if the createdOrderId is present
        if (createdOrderId == null) {
            throw new InvalidDataException(MISSING_ORDER_ID);
        }

        String baseUrl = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.SHAREGROOP_URL);

        // Init request
        URI uri;

        try {
            // Add the createOrderId to the url
            uri = new URI(baseUrl + createPath(PATH_VERSION, PATH_ORDER, createdOrderId));
        } catch (URISyntaxException e) {
            throw new InvalidDataException(SERVICE_URL_ERROR, e);
        }

        HttpGet httpGet = new HttpGet(uri);

        // Headers
        String privateKeyHolder = requestConfiguration.getContractConfiguration().getProperty(Constants.ContractConfigurationKeys.PRIVATE_KEY).getValue();
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeaders.AUTHORIZATION, privateKeyHolder);
        headers.put(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_VALUE);

        for (Map.Entry<String, String> h : headers.entrySet()) {
            httpGet.setHeader(h.getKey(), h.getValue());
        }

        // Execute request
        StringResponse response = this.execute(httpGet);

        return jsonService.fromJson(response.getContent(), SharegroopAPICallResponse.class);
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Verify if the private key is valid
     *
     * @param requestConfiguration
     * @return
     */
    public Boolean verifyPrivateKey(RequestConfiguration requestConfiguration) {
        StringResponse response = post(requestConfiguration,"","",null);

        if (response.getContent() == null){
            LOGGER.error("No response body");
            return false;
        }
        return response.getContent().contains("{\"status\":400,\"success\":false,\"errors\":[\"should be object\"]}");
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Create a transaction
     *
     * @param requestConfiguration
     * @return
     */
    public SharegroopAPICallResponse createOrder(RequestConfiguration requestConfiguration, Order order) {
        StringResponse response = post(requestConfiguration,"","",order.toString());

        return jsonService.fromJson(response.getContent(), SharegroopAPICallResponse.class);
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Refund each participant
     * @param requestConfiguration
     * @return
     */
    public SharegroopAPICallResponse refundOrder(RequestConfiguration requestConfiguration, String createdOrderId){
        StringResponse response = post(requestConfiguration,createdOrderId,REFUND,null);
        return jsonService.fromJson(response.getContent(), SharegroopAPICallResponse.class);
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Cancel an incompleted transaction
     * @param requestConfiguration
     * @param createdOrderId
     * @return
     */
    public SharegroopAPICallResponse cancelOrder(RequestConfiguration requestConfiguration, String createdOrderId){
        StringResponse response = post(requestConfiguration,createdOrderId,CANCEL,null);
        return jsonService.fromJson(response.getContent(), SharegroopAPICallResponse.class);
    }
    /**------------------------------------------------------------------------------------------------------------------*/
    /**
     * Manage Post API call
     * @param requestConfiguration
     * @param createdOrderId
     * @param path
     * @return
     */
    public StringResponse post(RequestConfiguration requestConfiguration, String createdOrderId, String path, String body){
        // Check if API url are present
        verifyPartnerConfigurationURL(requestConfiguration);

        String baseUrl = requestConfiguration.getPartnerConfiguration().getProperty(Constants.PartnerConfigurationKeys.SHAREGROOP_URL);

        // Init request
        URI uri;

        try {
            // Add the createOrderId to the url
            uri = new URI(baseUrl + createPath(PATH_VERSION, PATH_ORDER, createdOrderId,path));
        } catch (URISyntaxException e) {
            throw new InvalidDataException(SERVICE_URL_ERROR, e);
        }

        HttpPost httpPost = new HttpPost(uri);

        // Headers
        String privateKeyHolder = requestConfiguration.getContractConfiguration().getProperty(Constants.ContractConfigurationKeys.PRIVATE_KEY).getValue();
        Map<String, String> headers = new HashMap<>();
        headers.put(HttpHeaders.AUTHORIZATION, privateKeyHolder);
        headers.put(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE_VALUE);

        for (Map.Entry<String, String> h : headers.entrySet()) {
            httpPost.setHeader(h.getKey(), h.getValue());
        }

        // Body
        if(body != null) {
            httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        }

        // Execute request
        return this.execute(httpPost);
    }

}
