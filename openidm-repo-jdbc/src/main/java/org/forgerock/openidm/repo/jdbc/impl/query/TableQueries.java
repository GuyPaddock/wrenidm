/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2013 ForgeRock AS. All Rights Reserved
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

package org.forgerock.openidm.repo.jdbc.impl.query;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.repo.jdbc.impl.CleanupHelper;
import org.forgerock.openidm.repo.jdbc.impl.GenericTableHandler.QueryDefinition;
import org.forgerock.openidm.repo.jdbc.impl.TokenHandler;
import org.forgerock.openidm.smartevent.EventEntry;
import org.forgerock.openidm.smartevent.Name;
import org.forgerock.openidm.smartevent.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configured and add-hoc query support on tables in generic (non-object
 * specific) layout
 *
 * Queries can contain tokens of the format ${token-name}
 *
 * @author aegloff
 *
 */
public class TableQueries {

    final static Logger logger = LoggerFactory.getLogger(TableQueries.class);

    // Monitoring event name prefix
    static final String EVENT_RAW_QUERY_PREFIX = "openidm/internal/repo/jdbc/raw/query/";

    public static final String QUERY_ID = "_queryId";
    public static final String QUERY_EXPRESSION = "_queryExpression";

    // Pre-configured queries, key is query id
    Map<String, QueryInfo> queries = new HashMap<String, QueryInfo>();

    QueryResultMapper resultMapper;

    public TableQueries(QueryResultMapper resultMapper) {
        this.resultMapper = resultMapper;
    }

    /**
     * Get a prepared statement for the given connection and SQL. May come from
     * a cache (either local or the host container)
     *
     * @param connection
     *            db connection to get a prepared statement for
     * @param sql
     *            the prepared statement SQL
     * @return the prepared statement
     * @throws SQLException
     *             if parsing or retrieving the prepared statement failed
     */
    public PreparedStatement getPreparedStatement(Connection connection, String sql)
            throws SQLException {
        return getPreparedStatement(connection, sql, false);
    }

    /**
     * Get a prepared statement for the given connection and SQL. May come from
     * a cache (either local or the host container)
     *
     * @param connection
     *            db connection to get a prepared statement for
     * @param sql
     *            the prepared statement SQL
     * @param autoGeneratedKeys
     *            whether to return auto-generated keys by the DB
     * @return the prepared statement
     * @throws SQLException
     *             if parsing or retrieving the prepared statement failed
     */
    public PreparedStatement getPreparedStatement(Connection connection, String sql,
            boolean autoGeneratedKeys) throws SQLException {
        PreparedStatement statement = null;
        // This is where local prepared statement caching could be added for
        // stand-alone operation.

        // In the context of a (JavaEE) container rely on its built-in prepared
        // statement caching
        // rather than doing it explicitly here.
        if (autoGeneratedKeys) {
            statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        } else {
            statement = connection.prepareStatement(sql);
        }

        return statement;
    }

    /**
     * Get a prepared statement for the given connection and SQL. Returns the
     * generated Key This is a function used by OracleTableHandler. Since ORACLE
     * does not return the auto incremented key but the ROWID on using
     * getGeneratedKeys(), we have to pass a string array containing the column
     * that has been auto incremented. I.E. passing 'id' as the only entry of
     * this array to this method will return the value of the id-column instead
     * of the ROWID
     *
     * @param connection
     *            db connection to get a prepared statement for
     * @param sql
     *            the prepared statement SQL
     * @param columns
     *            which column shall be returned as the value of
     *            PreparedStatement.getGeneratedKeys()
     * @return the prepared statement
     * @throws SQLException
     *             if parsing or retrieving the prepared statement failed
     */
    public PreparedStatement getPreparedStatement(Connection connection, String sql,
            String[] columns) throws SQLException {
        PreparedStatement statement = null;

        statement = connection.prepareStatement(sql, columns);

        return statement;
    }

    /**
     * Execute a query, either a pre-configured query by using the query ID, or
     * a query expression passed as part of the params.
     *
     * The keys for the input parameters as well as the return map entries are
     * in QueryConstants.
     *
     * @param type
     *            the resource component name targeted by the URI
     * @param params
     *            the parameters which include the query id, or the query
     *            expression, as well as the token key/value pairs to replace in
     *            the query
     * @param con
     *            a handle to a database connection newBuilder for exclusive use
     *            by the query method whilst it is executing.
     * @return The query result, which includes meta-data about the query, and
     *         the result set itself.
     * @throws BadRequestException
     *             if the passed request parameters are invalid, e.g. missing
     *             query id or query expression or tokens.
     * @throws InternalServerErrorException
     *             if the preparing or executing the query fails because of
     *             configuration or DB issues
     */
    public List<Map<String, Object>> query(final String type, Map<String, Object> params, Connection con)
            throws BadRequestException, InternalServerErrorException {

        List<Map<String, Object>> result = null;
        params.put(ServerConstants.RESOURCE_NAME, type);


        String queryExpression = (String) params.get(QUERY_EXPRESSION);
        String queryId = (String) params.get(QUERY_ID);
        if (queryId == null && queryExpression == null) {
            throw new BadRequestException("Either " + QUERY_ID + " or " + QUERY_EXPRESSION
                    + " to identify/define a query must be passed in the parameters. " + params);
        }
        PreparedStatement foundQuery = null;
        try {
            if (queryExpression != null) {
                foundQuery = resolveInlineQuery(con, queryExpression, params);
            } else {
                foundQuery = getQuery(con, queryId, type, params);
            }
        } catch (SQLException ex) {
            throw new InternalServerErrorException("DB reported failure preparing query: "
                    + (foundQuery == null ? "null" : foundQuery.toString()) + " with params: " + params + " error code: " + ex.getErrorCode()
                    + " sqlstate: " + ex.getSQLState() + " message: " + ex.getMessage(), ex);
        }
        if (foundQuery == null) {
            throw new BadRequestException("The passed query identifier " + queryId
                    + " does not match any configured queries on the OrientDB repository service.");
        }

        Name eventName = getEventName(queryId);
        EventEntry measure = Publisher.start(eventName, foundQuery, null);
        ResultSet rs = null;
        try {
            rs = foundQuery.executeQuery();
            result = resultMapper.mapQueryToObject(rs, queryId, type, params, this);
            measure.setResult(result);
        } catch (SQLException ex) {
            throw new InternalServerErrorException("DB reported failure executing query "
                    + foundQuery.toString() + " with params: " + params + " error code: "
                    + ex.getErrorCode() + " sqlstate: " + ex.getSQLState() + " message: "
                    + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new InternalServerErrorException("Failed to convert result objects for query "
                    + foundQuery.toString() + " with params: " + params + " message: "
                    + ex.getMessage(), ex);
        } finally {
            CleanupHelper.loggedClose(rs);
            CleanupHelper.loggedClose(foundQuery);
            measure.end();
        }
        return result;
    }

    /**
     * Whether a result set contains a given column
     *
     * @param rsMetaData
     *            result set meta data
     * @param columnName
     *            name of the column to look for
     * @return true if it is present
     * @throws SQLException
     *             if meta data inspection failed
     */
    public boolean hasColumn(ResultSetMetaData rsMetaData, String columnName) throws SQLException {
        for (int colPos = 1; colPos <= rsMetaData.getColumnCount(); colPos++) {
            if (columnName.equalsIgnoreCase(rsMetaData.getColumnName(colPos))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a full query expression Currently does not support token
     * replacement
     *
     * @param con
     *            The db connection
     * @param queryExpression
     *            the native query string
     * @param params
     *            parameters passed to the resource query
     * @return A resolved statement
     */
    PreparedStatement resolveInlineQuery(Connection con, String queryExpression,
            Map<String, Object> params) throws SQLException {
        // No token replacement on expressions for now
        List<String> tokenNames = new ArrayList<String>();
        QueryInfo info = new QueryInfo(queryExpression, tokenNames);
        PreparedStatement stmt = resolveQuery(info, con, params);
        return stmt;
    }

    /**
     * Gets and resolves a query by id, using token substitution
     *
     * @param con
     *            The db connection
     * @param queryId
     *            the unique identifier of the paramterized, pre-defined query
     * @param type
     *            the resource component name targeted by the URI
     * @param params
     *            the paramteris passed into the query call
     * @return The statement
     * @throws SQLException
     *             if resolving the statement failed
     * @throws BadRequestException
     *             if no query is defined for the given identifier
     */
    PreparedStatement getQuery(Connection con, String queryId, String type,
            Map<String, Object> params) throws SQLException, BadRequestException {

        QueryInfo info = queries.get(queryId);
        if (info == null) {
            throw new BadRequestException("No query defined/configured for requested queryId "
                    + queryId);
        }
        PreparedStatement stmt = resolveQuery(info, con, params);
        return stmt;
    }

    /**
     * Resolves a query, given a QueryInfo
     *
     * @param info
     *            The info encapsulating the query information
     * @param con
     *            the db connection
     * @param params
     *            the paramters passed to query
     * @return the resolved query
     * @throws SQLException
     *             if resolving the query failed
     */
    PreparedStatement resolveQuery(QueryInfo info, Connection con, Map<String, Object> params)
            throws SQLException {
        String queryStr = info.getQueryString();
        List<String> tokenNames = info.getTokenNames();
        PreparedStatement statement = getPreparedStatement(con, queryStr);
        int count = 1; // DB column count starts at 1
        for (String tokenName : tokenNames) {
            Object objValue = params.get(tokenName);
            String value = null;
            if (objValue != null) {
                value = objValue.toString();
            }
            statement.setString(count, value);
            count++;
        }
        logger.debug("Prepared statement: {}", statement);

        return statement;
    }

    /**
     * Set the pre-configured queries for generic tables, which are identified
     * by a query identifier and can be invoked using this identifier
     *
     * Success to set the queries does not mean they are valid as some can only
     * be validated at query execution time.
     *
     * @param mainTableName
     *            name of the table containing the object identifier and all
     *            related info
     * @param propTableName
     *            name of the table holding individual properties for searching
     *            and indexing
     * @param dbSchemaName
     *            the database scheme the table is in
     * @param queriesConfig
     *            queries configured in configuration (files)
     * @param defaultQueryMap
     *            static default queries already defined for handling this table
     *            type
     *
     *            query details
     */
    public void setConfiguredQueries(String mainTableName, String propTableName,
            String dbSchemaName, JsonValue queriesConfig,
            Map<QueryDefinition, String> defaultQueryMap) {
        Map<String, String> replacements = new HashMap<String, String>();
        replacements.put("_mainTable", mainTableName);
        replacements.put("_propTable", propTableName);
        replacements.put("_dbSchema", dbSchemaName);
        setConfiguredQueries(replacements, queriesConfig, defaultQueryMap);
    }

    /**
     * Set the pre-configured queries for explicitly mapped tables, which are
     * identified by a query identifier and can be invoked using this identifier
     *
     * Success to set the queries does not mean they are valid as some can only
     * be validated at query execution time.
     *
     * @param tableName
     *            name of the explicitly mapped table
     * @param dbSchemaName
     *            the database scheme the table is in
     * @param queriesConfig
     *            queries configured in configuration (files)
     * @param defaultQueryMap
     *            static default queries already defined for handling this table
     *            type
     *
     *            query details
     */
    public void setConfiguredQueries(String tableName, String dbSchemaName,
            JsonValue queriesConfig, Map<QueryDefinition, String> defaultQueryMap) {
        Map<String, String> replacements = new HashMap<String, String>();
        replacements.put("_table", tableName);
        replacements.put("_dbSchema", dbSchemaName);
        setConfiguredQueries(replacements, queriesConfig, defaultQueryMap);
    }

    private void setConfiguredQueries(Map<String, String> replacements, JsonValue queriesConfig,
            Map<QueryDefinition, String> defaultQueryMap) {
        queries = new HashMap<String, QueryInfo>();

        if (queriesConfig == null || queriesConfig.isNull()) {
            queriesConfig = new JsonValue(new HashMap());
        }
        // Default query-all-ids to allow bootstrapping of configuration
        if (!queriesConfig.isDefined(ServerConstants.QUERY_ALL_IDS) && defaultQueryMap != null) {
            queriesConfig.put(ServerConstants.QUERY_ALL_IDS, defaultQueryMap
                    .get(QueryDefinition.QUERYALLIDS));
        }

        for (String queryName : queriesConfig.keys()) {
            String rawQuery = queriesConfig.get(queryName).required().asString();

            TokenHandler tokenHandler = new TokenHandler();
            // Replace the table name tokens.
            String tempQueryString = tokenHandler.replaceSomeTokens(rawQuery, replacements);

            // Convert to ? for prepared statement, populate token replacement
            // info
            List<String> tokenNames = tokenHandler.extractTokens(tempQueryString);
            String queryString = tokenHandler.replaceTokens(tempQueryString, "?");

            QueryInfo queryInfo = new QueryInfo(queryString, tokenNames);
            queries.put(queryName, queryInfo);
            logger.info("Configured query converted to JDBC query {} and tokens {}", queryString,
                    tokenNames);
        }
    }

    /**
     * @return the smartevent Name for a given query
     */
    Name getEventName(String queryId) {
        if (queryId == null) {
            return Name.get(EVENT_RAW_QUERY_PREFIX + "_query_expression");
        } else {
            return Name.get(EVENT_RAW_QUERY_PREFIX + queryId);
        }
    }
}
