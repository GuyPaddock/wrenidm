/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2014 ForgeRock AS. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */

package org.forgerock.openidm.provisioner.openicf.commons;

import org.codehaus.jackson.map.ObjectMapper;
import org.forgerock.json.crypto.JsonCryptoException;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.provisioner.openicf.connector.TestConfiguration;
import org.forgerock.openidm.provisioner.openicf.connector.TestConnector;
import org.forgerock.util.encode.Base64;
import org.identityconnectors.common.Assertions;
import org.identityconnectors.framework.api.APIConfiguration;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.api.ConnectorKey;
import org.identityconnectors.framework.api.operations.*;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.Schema;
import org.identityconnectors.framework.common.serializer.SerializerUtil;
import org.identityconnectors.framework.impl.api.APIConfigurationImpl;
import org.identityconnectors.test.common.TestHelpers;
import org.testng.Assert;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.File;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.forgerock.json.fluent.JsonValue.json;
import static org.forgerock.json.fluent.JsonValue.object;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sample Class Doc.
 */
public class ConnectorUtilTest {

    private APIConfiguration runtimeAPIConfiguration = null;
    private JsonValue jsonConfiguration;

    @BeforeTest
    public void beforeTest() throws Exception {
        TestConfiguration configuration = new TestConfiguration();
        runtimeAPIConfiguration = TestHelpers.createTestConfiguration(TestConnector.class, configuration);
        InputStream inputStream = null;
        try {
            inputStream = ConnectorUtilTest.class.getResourceAsStream("/config/TestSystemConnectorConfiguration.json");
            ObjectMapper mapper = new ObjectMapper();
            Map map = mapper.readValue(inputStream, Map.class);
            jsonConfiguration = new JsonValue(map);
        } finally {
            if (null != inputStream) {
                inputStream.close();
            }
        }
        Assert.assertNotNull(jsonConfiguration);
    }

    protected ConnectorFacade getFacade() {
        ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
        // **test only**
        return factory.newInstance(runtimeAPIConfiguration);
    }

    @Test
    public void testConfigureConfigurationProperties() {

        Map<String, Object> result = new HashMap<String, Object>();
        CryptoService cryptoServiceMock = mock(CryptoService.class);

        /**
         *  Values from runtimeAPIConfigurations to mock
         */
        JsonValue password = json("Password");
        JsonValue passw0rd = json("Passw0rd");
        JsonValue password1 = json("Passw0rd1");
        JsonValue password2 = json("Passw0rd2");
        JsonValue password3 = json("Password3");
        JsonValue password4 = json("Password4");

        try {
            /**
             * Used so that if refEq() is not one of the mocked out cases,
             * an empty object will be returned instead of NPE
             */
            when(cryptoServiceMock.encrypt(any(JsonValue.class), anyString(), anyString())).thenReturn(json(object()));

            when(cryptoServiceMock.encrypt(refEq(password) , anyString(), anyString())).thenReturn(json("..encrypted.."));
            when(cryptoServiceMock.encrypt(refEq(passw0rd) , anyString(), anyString())).thenReturn(json("..encrypted0.."));
            when(cryptoServiceMock.encrypt(refEq(password1) , anyString(), anyString())).thenReturn(json("..encrypted1.."));
            when(cryptoServiceMock.encrypt(refEq(password2) , anyString(), anyString())).thenReturn(json("..encrypted2.."));
            when(cryptoServiceMock.encrypt(refEq(password3) , anyString(), anyString())).thenReturn(json("..encrypted3.."));
            when(cryptoServiceMock.encrypt(refEq(password4) , anyString(), anyString())).thenReturn(json("..encrypted4.."));

            ConnectorUtil.setConfigurationProperties(runtimeAPIConfiguration.getConfigurationProperties(), result, cryptoServiceMock);
            JsonValue jsonResult = new JsonValue(result);
            assertThat(jsonResult.get("guardedByteArrayArrayValue").isList()).isTrue();
            assertThat(jsonResult.get("guardedByteArrayValue").asString()).isEqualTo("..encrypted0..");
            assertThat(jsonResult.get("guardedByteArrayArrayValue").get(0).asString()).isEqualTo("..encrypted1..");
            assertThat(jsonResult.get("guardedByteArrayArrayValue").get(1).asString()).isEqualTo("..encrypted2..");
            assertThat(jsonResult.get("guardedStringValue").asString()).isEqualTo("..encrypted..");
            assertThat(jsonResult.get("guardedStringArrayValue").get(0).asString()).isEqualTo("..encrypted3..");
            assertThat(jsonResult.get("guardedStringArrayValue").get(1).asString()).isEqualTo("..encrypted4..");

        } catch (JsonCryptoException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //@Test
    public void testGetConfiguration() throws Exception {
        JsonValue target = new JsonValue(new LinkedHashMap<String, Object>());
        ConnectorUtil.createSystemConfigurationFromAPIConfiguration(runtimeAPIConfiguration, target, null);
        APIConfiguration clonedConfiguration = getRuntimeAPIConfiguration();
        ConnectorUtil.configureDefaultAPIConfiguration(new JsonValue(target), clonedConfiguration, null);
        //Assert.assertEquals(clonedConfiguration, runtimeAPIConfiguration);

        ObjectMapper mapper = new ObjectMapper();
        URL root = ConnectorUtilTest.class.getResource("/");
        mapper.writeValue(new File((new URL(root, "runtimeAPIConfiguration.json")).toURI()), target);
    }

    @Test
    public void testGetAPIConfiguration() throws Exception {
        APIConfiguration clonedConfiguration = getRuntimeAPIConfiguration();
        ConnectorUtil.configureDefaultAPIConfiguration(jsonConfiguration, clonedConfiguration, null);
        Assert.assertFalse(clonedConfiguration.getResultsHandlerConfiguration().isEnableFilteredResultsHandler(), "\"enableFilteredResultsHandler\":false");
    }

    @Test
    public void testGetSchema() {
        ConnectorFacade connectorFacade = getFacade();
        Schema schema = connectorFacade.schema();
        Assert.assertNotNull(schema);
        JsonValue schemaMAP = new JsonValue(new LinkedHashMap<String, Object>(2));
        ConnectorUtil.setObjectAndOperationConfiguration(schema, schemaMAP);
        try {
            ObjectMapper mapper = new ObjectMapper();
            URL root = ConnectorUtilTest.class.getResource("/");
            mapper.writeValue(new File((new URL(root, "schema.json")).toURI()), schemaMAP);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

//    @Test
//    public void testOperationalSchema() throws Exception {
//        InputStream inputStream = ConnectorUtilTest.class.getResourceAsStream("/config/SystemSchemaConfiguration.json");
//        Assert.assertNotNull(inputStream);
//        ObjectMapper mapper = new ObjectMapper();
//        JsonValue configuration = new JsonValue(mapper.readValue(inputStream, Map.class));
//        Map<String, Map<Class<? extends APIOperation>, OperationOptionInfoHelper>> operationOptionHelpers = ConnectorUtil.getOperationOptionConfiguration(configuration);
//        ObjectClassInfoHelper objectClassInfoHelper = org.mockito.Mockito.mock(ObjectClassInfoHelper.class);
//        org.mockito.Mockito.when(objectClassInfoHelper.getObjectClass()).thenReturn(new ObjectClass("__ACCOUNT__"));
//        OperationHelper helper = new OperationHelper(new Id("system/TEST/account"), objectClassInfoHelper, operationOptionHelpers.get("__ACCOUNT__"), null);
//
//        Assert.assertTrue(helper.isOperationPermitted(CreateApiOp.class), "Create - ALLOWED");
//        Assert.assertFalse(helper.isOperationPermitted(SyncApiOp.class), "Sync - DENIED");
//
//        boolean authenticated = true;
//        try {
//            helper.isOperationPermitted(AuthenticationApiOp.class);
//        } catch (JsonResourceException e) {
//            authenticated = false;
//        }
//        Assert.assertFalse(authenticated, "Authentication - DENIED(Exception)");
//
//        boolean operationSupported = true;
//        try {
//            helper.isOperationPermitted(ScriptOnResourceApiOp.class);
//        } catch (JsonResourceException e) {
//            operationSupported = false;
//        }
//        Assert.assertFalse(operationSupported, "ScriptOnResource - NotSupported(Exception)");
//
//        Assert.assertFalse(helper.isOperationPermitted(ScriptOnConnectorApiOp.class), "ScriptOnConnector - DENIED");
//        Assert.assertFalse(helper.isOperationPermitted(SearchApiOp.class), "Search - DENIED");
//    }
//
//
//    @Test
//    public void testAPIConfiguration() throws JsonValueException, SchemaException, URISyntaxException, JsonResourceException {
//        ConnectorReference connectorReference = ConnectorUtil.getConnectorReference(jsonConfiguration);
//        Assert.assertEquals(connectorReference.getConnectorHost(), ConnectorReference.SINGLE_LOCAL_CONNECTOR_MANAGER);
//        ConnectorKey key = connectorReference.getConnectorKey();
//        Assert.assertEquals(key.getBundleName(), "org.identityconnectors.ldap");
//        Assert.assertEquals(key.getBundleVersion(), "1.0.5531");
//        Assert.assertEquals(key.getConnectorName(), "org.identityconnectors.ldap.LdapConnector");
//
//        SystemIdentifier systemIdentifier = new SimpleSystemIdentifier(jsonConfiguration);
//        Assert.assertTrue(systemIdentifier.is(new Id("http://openidm.forgerock.org/openidm/system/LDAP_Central/user/CA2B382A-6FFB-11E0-80B7-902C4824019B")));
//        Assert.assertTrue(systemIdentifier.is(new Id("system/LDAP_Central/account/")));
//        Assert.assertFalse(systemIdentifier.is(new Id("http://openidm.forgerock.org/openidm/system/LDAP_None/user/CA2B382A-6FFB-11E0-80B7-902C4824019B")));
//        Assert.assertFalse(systemIdentifier.is(new Id("system/LDAP_None/account")));
//
//        OperationHelperBuilder operationHelperBuilder = new OperationHelperBuilder(((SimpleSystemIdentifier) systemIdentifier).getName(), jsonConfiguration, runtimeAPIConfiguration);
//
//        OperationHelper helper = operationHelperBuilder.build("__ACCOUNT__", null, null);
//        Assert.assertEquals(helper.getObjectClass().getObjectClassValue(), "__ACCOUNT__");
//    }
//
//    @Test(expectedExceptions = JsonResourceException.class, expectedExceptionsMessageRegExp = ".*__NONE__.*")
//    public void testUnsupportedObjectType() throws JsonValueException, SchemaException, URISyntaxException, JsonResourceException {
//        OperationHelperBuilder operationHelperBuilder = new OperationHelperBuilder("test", jsonConfiguration, runtimeAPIConfiguration);
//        OperationHelper helper = operationHelperBuilder.build("__NONE__", null, null);
//    }

    @Test
    public void testCoercedTypeCasting() throws Exception {
        BigInteger bigInteger = ConnectorUtil.coercedTypeCasting(new Integer(20), BigInteger.class);
        Assert.assertEquals(bigInteger.intValue(), 20);
        Boolean booleanValue = ConnectorUtil.coercedTypeCasting("true",boolean.class);
        Assert.assertTrue(booleanValue);
        Integer integerValue = ConnectorUtil.coercedTypeCasting("636",int.class);
        Assert.assertEquals(integerValue, (Integer)636);
        float floatValue = ConnectorUtil.coercedTypeCasting("636",float.class);
        Assert.assertEquals(floatValue, 636.0f);
    }

    @Test void testCoercedTypeCastingForByte() {
        // String -> Byte
        Byte byteValueFromString = ConnectorUtil.coercedTypeCasting("100", Byte.class);
        Assert.assertEquals(byteValueFromString.byteValue(), 100);
        // Integer -> Byte
        Byte byteValueFromNumber = ConnectorUtil.coercedTypeCasting(10, Byte.class);
        Assert.assertEquals(byteValueFromNumber.byteValue(), 10);
        // Byte -> Byte
        Byte byteValueFromBoxedByte = ConnectorUtil.coercedTypeCasting(new Byte("124"), Byte.class);
        Assert.assertEquals(byteValueFromBoxedByte.byteValue(), 124);
        // byte -> Byte
        Byte byteValueFromPrimitiveByte = ConnectorUtil.coercedTypeCasting((byte) 10, Byte.class);
        Assert.assertEquals(byteValueFromPrimitiveByte.byteValue(), 10);
        // Byte -> String
        String stringFromBoxedByte = ConnectorUtil.coercedTypeCasting(new Byte("10"), String.class);
        Assert.assertEquals(stringFromBoxedByte, Base64.encode(new byte[] {new Byte("10")}));
        // byte -> String
        String stringFromPrimitiveByte = ConnectorUtil.coercedTypeCasting((byte) 10, String.class);
        Assert.assertEquals(stringFromPrimitiveByte, Base64.encode(new byte[] {(byte)10}));
    }

    @Test void testCoercedTypeCastingForByteType() {
        // String -> byte
        byte byteValueFromString = ConnectorUtil.coercedTypeCasting("100", Byte.TYPE);
        Assert.assertEquals(byteValueFromString, 100);
        //Integer -> byte
        byte byteValueFromNumber = ConnectorUtil.coercedTypeCasting(10, Byte.TYPE);
        Assert.assertEquals(byteValueFromNumber, 10);
        // Byte -> byte
        byte byteValueFromBoxedByte = ConnectorUtil.coercedTypeCasting(new Byte("124"), Byte.TYPE);
        Assert.assertEquals(byteValueFromBoxedByte, 124);
        // byte -> byte
        byte byteValueFromPrimitiveByte = ConnectorUtil.coercedTypeCasting((byte) 10, Byte.TYPE);
        Assert.assertEquals(byteValueFromPrimitiveByte, 10);
    }

    public APIConfiguration getRuntimeAPIConfiguration() {
        Assertions.nullCheck(runtimeAPIConfiguration, "runtimeAPIConfiguration");
        //clone in case application tries to modify
        //after the fact. this is necessary to
        //ensure thread-safety of a ConnectorFacade
        //also, runtimeAPIConfiguration is used as a key in the
        //pool, so it is important that it not be modified.
        APIConfigurationImpl _configuration = (APIConfigurationImpl) SerializerUtil.cloneObject(runtimeAPIConfiguration);
        //parent ref not included in the clone
        _configuration.setConnectorInfo(((APIConfigurationImpl) runtimeAPIConfiguration).getConnectorInfo());
        return _configuration;
    }
}
