/**
 * Copyright 2014 Microsoft Open Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.microsoftopentechnologies.intellij.helpers;

import com.google.common.util.concurrent.ListenableFuture;
import com.intellij.ide.util.PropertiesComponent;
import com.microsoftopentechnologies.intellij.components.MSOpenTechTools;
import com.microsoftopentechnologies.intellij.components.PluginSettings;
import com.microsoftopentechnologies.intellij.helpers.aadauth.AuthenticationContext;
import com.microsoftopentechnologies.intellij.helpers.aadauth.AuthenticationResult;
import com.microsoftopentechnologies.intellij.model.Subscription;
import org.apache.xerces.dom.DeferredElementImpl;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import sun.misc.BASE64Decoder;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;

public class AzureRestAPIHelper {

    public static final String AZURE_API_VERSION = "2014-06-01";
    public static final String USER_AGENT_HEADER = "User-Agent";
    public static final String TELEMETRY_HEADER = "X-ClientService-ClientTag";
    public static final String X_MS_VERSION_HEADER = "x-ms-version";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String ACCEPT_HEADER = "Accept";
    public static final String CONTENT_TYPE_HEADER = "Content-Type";

    public static void removeSubscription(String subscriptionId) throws SAXException, ParserConfigurationException, XPathExpressionException, IOException, TransformerException {

        String existingXml = PropertiesComponent.getInstance().getValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE);

        NodeList subscriptionList = (NodeList) getXMLValue(existingXml, "//Subscription", XPathConstants.NODESET);

        for (int i = 0; i < subscriptionList.getLength(); i++) {
            String id = getAttributeValue(subscriptionList.item(i), "Id");
            if (id.equals(subscriptionId))
                subscriptionList.item(i).getParentNode().removeChild(subscriptionList.item(i));
        }

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(subscriptionList.item(0).getOwnerDocument()), new StreamResult(writer));

        PropertiesComponent.getInstance().setValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE, writer.getBuffer().toString());
    }

    public static void importSubscription(File publishSettingsFile) throws AzureCmdException {

        try {
            BufferedReader isfile = new BufferedReader(new InputStreamReader(new FileInputStream(publishSettingsFile)));
            String line = isfile.readLine();
            String publishInfo = "";
            while (line != null) {
                publishInfo = publishInfo + line;
                line = isfile.readLine();
            }

            String xml = OpenSSLHelper.processCertificate(publishInfo);

            if (!PropertiesComponent.getInstance().getValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE, "").trim().isEmpty()) {
                String existingXml = PropertiesComponent.getInstance().getValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE);

                NodeList subscriptionList = (NodeList) getXMLValue(existingXml, "//Subscription", XPathConstants.NODESET);
                Node newSubscription = ((NodeList) getXMLValue(xml, "//Subscription", XPathConstants.NODESET)).item(0);

                if (subscriptionList.getLength() == 0) {
                    PropertiesComponent.getInstance().setValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE, xml);
                } else {

                    Document ownerDocument = subscriptionList.item(0).getOwnerDocument();
                    Node parentNode = subscriptionList.item(0).getParentNode();

                    for (int i = 0; i < subscriptionList.getLength(); i++) {
                        String newId = getAttributeValue(newSubscription, "Id");
                        String id = getAttributeValue(subscriptionList.item(i), "Id");
                        if (id.equals(newId))
                            subscriptionList.item(i).getParentNode().removeChild(subscriptionList.item(i));
                    }

                    Node newNode = ownerDocument.importNode(newSubscription, true);
                    Node node = parentNode.appendChild(newNode);

                    TransformerFactory tf = TransformerFactory.newInstance();
                    Transformer transformer = tf.newTransformer();
                    StringWriter writer = new StringWriter();
                    transformer.transform(new DOMSource(node.getOwnerDocument()), new StreamResult(writer));

                    PropertiesComponent.getInstance().setValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE, writer.getBuffer().toString());
                }
            } else {
                PropertiesComponent.getInstance().setValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE, xml);
            }
        } catch (AzureCmdException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AzureCmdException("Error importing subscription", ex);
        }
    }

    public static Object getXMLValue(String xml, String xQuery, QName resultType) throws XPathExpressionException, IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();

        Document doc = db.parse(new InputSource(new StringReader(xml)));

        XPathFactory xPathfactory = XPathFactory.newInstance();

        XPath xPath = xPathfactory.newXPath();
        XPathExpression xPathExpression = xPath.compile(xQuery);
        return xPathExpression.evaluate(doc, resultType);
    }

    public static String getAttributeValue(Node node, String attributeName) {
        Node n = node.getAttributes().getNamedItem(attributeName);
        return (n == null) ? null : n.getNodeValue();
    }

    public static String getChildNodeValue(Node node, String elementName) {
        return ((DeferredElementImpl) node).getElementsByTagName(elementName).item(0).getTextContent();
    }

    public static String getRestApiCommand(String path, String subscriptionId)
            throws IOException,
                   SAXException,
                   ParserConfigurationException,
                   XPathExpressionException,
                   NoSuchAlgorithmException,
                   KeyStoreException,
                   CertificateException,
                   UnrecoverableKeyException,
                   KeyManagementException,
                   NoSubscriptionException,
                   AzureCmdException,
                   ExecutionException,
                   InterruptedException {

        AzureRestCallbackAdapter<String> callback = new AzureRestCallbackAdapter<String>() {
            @Override
            public int apply(HttpsURLConnection sslConnection) throws IOException {
                int response = sslConnection.getResponseCode();
                if (sslConnection.getResponseCode() < 400) {
                    setResult(readStream(sslConnection.getInputStream(), true));
                }

                return response;
            }
        };

        runWithSSLConnection(path, true, subscriptionId, callback);
        if(!callback.isOk()) {
            throw callback.getError();
        }
        return callback.getResult();
    }

    public static String postRestApiCommand(String path, String postData, String subscriptionId, String asyncUrl, boolean jsonContent) throws IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, ParserConfigurationException, SAXException, KeyStoreException, XPathExpressionException, KeyManagementException, AzureCmdException, InterruptedException, NoSubscriptionException, ExecutionException {
        return restApiCommand("POST", path, postData, subscriptionId, asyncUrl, jsonContent);
    }

    public static String putRestApiCommand(String path, String postData, String subscriptionId, String asyncUrl, boolean jsonContent) throws IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, ParserConfigurationException, SAXException, KeyStoreException, XPathExpressionException, KeyManagementException, AzureCmdException, InterruptedException, NoSubscriptionException, ExecutionException {
        return restApiCommand("PUT", path, postData, subscriptionId, asyncUrl, jsonContent);
    }

    private static String restApiCommand(
            final String method,
            final String path,
            final String postData,
            final String subscriptionId,
            final String asyncUrl,
            final boolean jsonContent)
            throws IOException,
                   CertificateException,
                   NoSuchAlgorithmException,
                   UnrecoverableKeyException,
                   ParserConfigurationException,
                   SAXException,
                   KeyStoreException,
                   XPathExpressionException,
                   KeyManagementException,
                   AzureCmdException,
                   InterruptedException,
                   NoSubscriptionException,
                   ExecutionException {

        // This is a callback method that is invoked in a loop below after the request has been
        // sent. The purpose of this method is to check the status of the pending operation and check
        // if it is complete.
        final AzureRestCallbackAdapter<Boolean> requestStatusCallback = new AzureRestCallbackAdapter<Boolean>() {
            @Override
            public int apply(HttpsURLConnection sslConnection) throws IOException {
                setResult(false);

                try {
                    int responseCode = sslConnection.getResponseCode();
                    if (responseCode < 200 && responseCode > 299) {
                        setError(new AzureCmdException("Operation interrupted", "Http error code: "
                                + String.valueOf(responseCode)));
                    } else {
                        String pollres = readStream(sslConnection.getInputStream());
                        NodeList nl = ((NodeList) getXMLValue(pollres, "//Status", XPathConstants.NODESET));
                        if (nl.getLength() > 0) {
                            if (nl.item(0).getTextContent().equals("Succeeded")) {
                                setResult(true);
                            }
                        }
                    }

                    return responseCode;
                }
                catch(Exception e) {
                    setError(new AzureCmdException(e.getMessage(), e));
                    return HttpURLConnection.HTTP_INTERNAL_ERROR;
                }
            }
        };

        AzureRestCallbackAdapter<String> callback = new AzureRestCallbackAdapter<String>() {
            @Override
            public int apply(HttpsURLConnection sslConnection) throws IOException {
                setError(null);

                sslConnection.setRequestMethod(method);
                sslConnection.setDoOutput(true);
                sslConnection.setRequestProperty("Accept", "");

                DataOutputStream wr = new DataOutputStream(sslConnection.getOutputStream());
                wr.writeBytes(postData);
                wr.flush();
                wr.close();

                int responseCode = sslConnection.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    String response = readStream(sslConnection.getInputStream());

                    if (responseCode == 202 && asyncUrl != null) {
                        String operationURL = asyncUrl + sslConnection.getHeaderField("x-ms-request-id");
                        sslConnection.disconnect();

                        boolean succeed = false;
                        while(!succeed) {
                            try {
                                runWithSSLConnection(operationURL, false, subscriptionId, requestStatusCallback);
                                if(!requestStatusCallback.isOk()) {
                                    setError(requestStatusCallback.getError());

                                    // NOTE: setting "succeed" to false below means that the loop for
                                    // checking the status of the request will continue running; the hope is
                                    // that the error that occurred while checking for status is transient
                                    // and won't occur again;
                                    // TODO: A better approach might be to retry a few times and then bail.
                                    succeed = false;
                                } else {
                                    succeed = requestStatusCallback.getResult();
                                }

                                if(!succeed) {
                                    // wait for a while otherwise Azure complains with a
                                    // "too many requests received" error
                                    // TODO: This is a bit hacky. See if we can do better.
                                    Thread.sleep(2000);
                                }
                            }
                            catch(Exception e) {
                                setError(new AzureCmdException(e.getMessage(), e));
                                return responseCode;
                            }
                        }
                    }

                    setResult(response);

                } else {
                    String err = readStream(sslConnection.getErrorStream(), true);
                    setError(new AzureCmdException("Error uploading script: ", err));
                }

                return responseCode;
            }
        };

        runWithSSLConnection(path, jsonContent, subscriptionId, callback);
        if(!callback.isOk()) {
            throw callback.getError();
        }
        return callback.getResult();
    }

    interface AzureRestCallback<T> {
        @Nullable
        int apply(HttpsURLConnection sslConnection) throws IOException;

        T getResult();
        void setResult(T result);

        AzureCmdException getError();
        void setError(AzureCmdException throwable);

        boolean isOk();

        @Override
        boolean equals(@Nullable java.lang.Object o);
    }

    abstract static class AzureRestCallbackAdapter<T> implements AzureRestCallback<T> {
        private T result;
        private AzureCmdException azureError = null;

        @Override
        public T getResult() {
            return result;
        }

        @Override
        public void setResult(T result) {
            this.result = result;
        }

        @Override
        public AzureCmdException getError() {
            return azureError;
        }

        @Override
        public void setError(AzureCmdException throwable) {
            this.azureError = throwable;
        }

        @Override
        public boolean isOk() {
            return azureError == null;
        }
    }

    public static <T> void runWithSSLConnection(
            String path,
            boolean jsonContent,
            String subscriptionId,
            AzureRestCallback<T> callback)
            throws IOException,
                   CertificateException,
                   NoSuchAlgorithmException,
                   UnrecoverableKeyException,
                   ParserConfigurationException,
                   SAXException,
                   KeyStoreException,
                   XPathExpressionException,
                   KeyManagementException,
                   ExecutionException,
                   InterruptedException,
                   AzureCmdException,
                   NoSubscriptionException {

        AzureManager apiManager = AzureRestAPIManager.getManager();
        AzureAuthenticationMode authMode = apiManager.getAuthenticationMode();
        if(authMode == AzureAuthenticationMode.ActiveDirectory) {
            runWithSSLConnectionFromToken(path, jsonContent, subscriptionId, callback, apiManager);
        }
        else if(authMode == AzureAuthenticationMode.SubscriptionSettings) {
            runWithSSLConnectionFromCert(path, jsonContent, subscriptionId, callback);
        }
        else {
            throw new NoSubscriptionException("A valid Azure subscription has not been configured yet.");
        }
    }

    private static <T> void runWithSSLConnectionFromCert(
            String path,
            boolean jsonContent,
            String subscriptionId,
            AzureRestCallback<T> callback) throws IOException, KeyManagementException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException, CertificateException, ParserConfigurationException, XPathExpressionException, SAXException, AzureCmdException {

        HttpsURLConnection sslConnection = null;
        sslConnection = getSSLConnectionFromCert(path, jsonContent, subscriptionId);
        int response = callback.apply(sslConnection);
        if (response < 200 || response > 299) {
            throw new AzureCmdException("Error connecting to service", readStream(sslConnection.getErrorStream()));
        }
    }

    private static <T> void runWithSSLConnectionFromToken(
            String path,
            boolean jsonContent,
            String subscriptionId,
            AzureRestCallback<T> callback,
            AzureManager apiManager) throws IOException, KeyManagementException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException, CertificateException, ParserConfigurationException, XPathExpressionException, SAXException, InterruptedException, ExecutionException, AzureCmdException, NoSubscriptionException {

        HttpsURLConnection sslConnection;// there should already be a valid auth token by this time
        boolean isForSubscription = !StringHelper.isNullOrWhiteSpace(subscriptionId);

        if(!isForSubscription && apiManager.getAuthenticationToken() == null) {
            throw new UnsupportedOperationException("The authentication mode has been set to use AD " +
                "but no valid access token found. Please sign in to your account.");
        }

        // if this call is for a specific subscription and we don't have an authentication token for
        // that subscription then acquire one
        if(isForSubscription && apiManager.getAuthenticationTokenForSubscription(subscriptionId) == null) {
            // perform interactive authentication
            acquireTokenInteractive(subscriptionId, apiManager);
        }

        sslConnection = getSSLConnectionFromAccessToken(path, jsonContent, subscriptionId, false);
        int response = callback.apply(sslConnection);

        if(response == HttpURLConnection.HTTP_UNAUTHORIZED) {
            // retry with refresh token
            sslConnection = getSSLConnectionFromAccessToken(path, jsonContent, subscriptionId, true);

            // sslConnection will be null if we don't have a refresh token; in which
            // we fall through to the next "if" check where we attempt interactive auth
            if(sslConnection != null) {
                response = callback.apply(sslConnection);
            }

            if(response == HttpURLConnection.HTTP_UNAUTHORIZED) {
                // perform interactive authentication
                acquireTokenInteractive(subscriptionId, apiManager);

                // third time lucky?
                sslConnection = getSSLConnectionFromAccessToken(path, jsonContent, subscriptionId, false);
                response = callback.apply(sslConnection);
                if(response < 200 || response > 299) {
                    // clear the auth token
                    apiManager.setAuthenticationToken(null);
                    throw new AzureCmdException("Error connecting to service", readStream(sslConnection.getErrorStream()));
                }
            }
            else if(response < 200 || response > 299) {
                throw new AzureCmdException("Error connecting to service", readStream(sslConnection.getErrorStream()));
            }
        } else if(response < 200 || response > 299) {
            throw new AzureCmdException("Error connecting to service", readStream(sslConnection.getErrorStream()));
        }
    }

    private static AuthenticationResult acquireTokenInteractive(
            String subscriptionId, AzureManager apiManager) throws IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, ExecutionException, ParserConfigurationException, InterruptedException, XPathExpressionException, SAXException, KeyManagementException, KeyStoreException, AzureCmdException, NoSubscriptionException {

        PluginSettings settings = MSOpenTechTools.getCurrent().getSettings();
        AuthenticationContext context = null;
        AuthenticationResult token = null;
        boolean isForSubscription = !StringHelper.isNullOrWhiteSpace(subscriptionId);

        try {
            context = new AuthenticationContext(settings.getAdAuthority());
            String windowTitle = isForSubscription ?
                    "Sign in: " +
                            apiManager.getSubscriptionFromId(subscriptionId).getName() :
                    "Sign in to your Microsoft account";
            ListenableFuture<AuthenticationResult> future = context.acquireTokenInteractiveAsync(
                    getTenantName(subscriptionId),
                    settings.getAzureServiceManagementUri(),
                    settings.getClientId(),
                    settings.getRedirectUri(),
                    null,
                    windowTitle);
            token = future.get();

            // save the token
            if(isForSubscription) {
                apiManager.setAuthenticationTokenForSubscription(subscriptionId, token);
            } else {
                apiManager.setAuthenticationToken(token);
            }
        }
        finally {
            if (context != null) {
                context.dispose();
            }
        }

        return token;
    }

    private static HttpsURLConnection getSSLConnectionFromAccessToken(
            String path,
            boolean jsonContent,
            String subscriptionId,
            boolean useRefreshToken) throws IOException, KeyManagementException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException, CertificateException, ParserConfigurationException, XPathExpressionException, SAXException, InterruptedException, ExecutionException, AzureCmdException, NoSubscriptionException {

        PluginSettings settings = MSOpenTechTools.getCurrent().getSettings();
        AzureManager apiManager = AzureRestAPIManager.getManager();

        // get the default token if there is no subscription ID; otherwise fetch
        // the token for the subscription
        boolean isForSubscription = !StringHelper.isNullOrWhiteSpace(subscriptionId);
        AuthenticationResult token = isForSubscription ?
                apiManager.getAuthenticationTokenForSubscription(subscriptionId) :
                apiManager.getAuthenticationToken();

        // get a new access token if "useRefreshToken" is true
        if(useRefreshToken) {
            // check if we have a refresh token to redeem
            if(StringHelper.isNullOrWhiteSpace(token.getRefreshToken())) {
                return null;
            }

            AuthenticationContext context = new AuthenticationContext(settings.getAdAuthority());
            try {
                token = context.acquireTokenByRefreshToken(
                            token,
                            getTenantName(subscriptionId),
                            settings.getAzureServiceManagementUri(),
                            settings.getClientId());
            }
            finally {
                context.dispose();
            }

            if(isForSubscription) {
                apiManager.setAuthenticationTokenForSubscription(subscriptionId, token);
            } else {
                apiManager.setAuthenticationToken(token);
            }
        }

        String url = settings.getAzureServiceManagementUri();
        URL myUrl = new URL(new URL(url), path);

        // Uncomment following two lines to capture traffic in Fiddler. You'll need to
        // import the Fiddler cert as a trusted root cert in the JRE first though.
        //Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 8888));
        //HttpsURLConnection conn = (HttpsURLConnection) myUrl.openConnection(proxy);

        HttpsURLConnection conn = (HttpsURLConnection) myUrl.openConnection();
        conn.addRequestProperty(USER_AGENT_HEADER, getPlatformUserAgent());
        conn.addRequestProperty(TELEMETRY_HEADER, getPlatformUserAgent());
        conn.addRequestProperty(X_MS_VERSION_HEADER, AZURE_API_VERSION);
        if (jsonContent) {
            conn.addRequestProperty(ACCEPT_HEADER, "application/json");
            conn.addRequestProperty(CONTENT_TYPE_HEADER, "application/json");
        } else {
            conn.addRequestProperty(ACCEPT_HEADER, "application/xml");
            conn.addRequestProperty(CONTENT_TYPE_HEADER, "application/xml");
        }

        // set access token
        conn.addRequestProperty(AUTHORIZATION_HEADER, "Bearer " + token.getAccessToken());

        return conn;
    }

    private static String getPlatformUserAgent() {
        String version = MSOpenTechTools.getCurrent().getSettings().getPluginVersion();
        return String.format(
                "%s/%s (lang=%s; os=%s; version=%s)",
                MSOpenTechTools.PLUGIN_ID,
                version,
                "Java",
                System.getProperty("os.name"),
                version);
    }

    private static String getTenantName(String subscriptionId) throws IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, ExecutionException, ParserConfigurationException, InterruptedException, SAXException, AzureCmdException, NoSubscriptionException, KeyStoreException, XPathExpressionException, KeyManagementException {
        // get tenant id from subscription if this request is for an
        // azure subscription
        String tenantName = MSOpenTechTools.getCurrent().getSettings().getTenantName();
        if(!StringHelper.isNullOrWhiteSpace(subscriptionId)) {
            Subscription subscription = AzureRestAPIManager.getManager().getSubscriptionFromId(subscriptionId);
            if(subscription != null) {
                tenantName = subscription.getTenantId();
            }
        }
        return tenantName;
    }

    private static HttpsURLConnection getSSLConnectionFromCert(
            String path,
            boolean jsonContent,
            String subscriptionId)
            throws IOException,
                   KeyManagementException,
                   NoSuchAlgorithmException,
                   UnrecoverableKeyException,
                   KeyStoreException,
                   CertificateException,
                   ParserConfigurationException,
                   XPathExpressionException,
                   SAXException {

        String publishSettings = PropertiesComponent.getInstance().getValue(MSOpenTechTools.AppSettingsNames.SUBSCRIPTION_FILE, "");
        if (publishSettings.isEmpty())
            return null;

        Node node = null;

        NodeList subslist = (NodeList) getXMLValue(
                publishSettings,
                "//PublishData/PublishProfile/Subscription",
                XPathConstants.NODESET);
        for (int i = 0; i < subslist.getLength(); i++) {
            String id = getAttributeValue(subslist.item(i), "Id");
            if (id.equals(subscriptionId))
                node = subslist.item(i);
        }

        if (node == null)
            return null;

        String pfx = getAttributeValue(node, "ManagementCertificate");
        String url = getAttributeValue(node, "ServiceManagementUrl");

        byte[] decodeBuffer = new BASE64Decoder().decodeBuffer(pfx);

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");

        InputStream is = new ByteArrayInputStream(decodeBuffer);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(is, OpenSSLHelper.PASSWORD.toCharArray());
        keyManagerFactory.init(ks, OpenSSLHelper.PASSWORD.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

        URL myUrl = new URL(url + path);
        HttpsURLConnection conn = (HttpsURLConnection) myUrl.openConnection();
        conn.setSSLSocketFactory(sslContext.getSocketFactory());
        conn.addRequestProperty(USER_AGENT_HEADER, getPlatformUserAgent());
        conn.addRequestProperty(TELEMETRY_HEADER, getPlatformUserAgent());
        conn.addRequestProperty(X_MS_VERSION_HEADER, AZURE_API_VERSION);
        if (jsonContent) {
            conn.addRequestProperty(ACCEPT_HEADER, "application/json");
            conn.addRequestProperty(CONTENT_TYPE_HEADER, "application/json");
        } else {
            conn.addRequestProperty(ACCEPT_HEADER, "application/xml");
            conn.addRequestProperty(CONTENT_TYPE_HEADER, "application/xml");
        }

        return conn;
    }

    private static String readStream(InputStream is) throws IOException {
        return readStream(is, false);
    }

    private static String readStream(InputStream is, boolean keepLines) throws IOException {
        BufferedReader in = null;
        try {
            in = new BufferedReader(
                    new InputStreamReader(is));
            String inputLine;
            String separator = System.getProperty("line.separator");
            StringBuilder response = new StringBuilder();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
                if (keepLines) {
                    response.append(separator);
                }
            }
            in.close(); in = null;
            return response.toString();
        }
        finally {
            if(in != null) {
                in.close();
            }
        }
    }

    public static void uploadScript(final String path, final String filePath, final String subscriptionId)
            throws IOException,
                   CertificateException,
                   NoSuchAlgorithmException,
                   UnrecoverableKeyException,
                   ParserConfigurationException,
                   SAXException,
                   KeyStoreException,
                   XPathExpressionException,
                   KeyManagementException,
                   AzureCmdException,
                   InterruptedException,
                   NoSubscriptionException,
                   ExecutionException {

        runWithSSLConnection(path, true, subscriptionId, new AzureRestCallbackAdapter<Void>() {
            @Override
            public int apply(HttpsURLConnection sslConnection) throws IOException {
                String script = readStream(new FileInputStream(filePath), true);

                sslConnection.setRequestMethod("PUT");
                sslConnection.setDoOutput(true);
                sslConnection.setRequestProperty("Accept", "");
                sslConnection.setRequestProperty("Content-Type", "text/plain");

                DataOutputStream wr = new DataOutputStream(sslConnection.getOutputStream());
                wr.writeBytes(script);
                wr.flush();
                wr.close();

                return sslConnection.getResponseCode();
            }
        });
    }

    public static boolean existsMobileService(String name) {
        try {
            URL myUrl = new URL("https://" + name + ".azure-mobile.net");
            HttpsURLConnection conn = (HttpsURLConnection) myUrl.openConnection();

            int responseCode = conn.getResponseCode();
            return (responseCode >= 200 && responseCode < 300);
        } catch (Exception e) {
            return false;
        }
    }
}
