/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */
package tigase.db.jdbc;

import java.math.BigDecimal;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;

import tigase.util.Base64;
import tigase.auth.SaslPLAIN;
import tigase.db.AuthorizationException;
import tigase.db.DBInitException;
import tigase.db.TigaseDBException;
import tigase.db.UserAuthRepository;
import tigase.db.UserExistsException;
import tigase.db.UserNotFoundException;
import tigase.util.Algorithms;
import tigase.util.JIDUtils;

import static tigase.db.UserAuthRepository.*;

/**
 * The user authentication connector allows for customized SQL queries to be used.
 * Queries are defined in the configuration file and they can be either plain SQL
 * queries or stored procedures.
 *
 * If the query starts with characters: <code>{ call</code> then the server
 * assumes this is a stored procedure call, otherwise it is executed as a plain
 * SQL query. Each configuration value is stripped from white characters on both
 * ends before processing.
 *
 * Please don't use semicolon <code>';'</code> at the end of the query as many
 * JDBC drivers get confused and the query may not work for unknown obious
 * reason.
 *
 * Some queries take arguments. Arguments are marked by question marks
 * <code>'?'</code> in the query. Refer to the configuration parameters
 * description for more details about what parameters are expected in each
 * query.
 *
 * Example configuration.
 *
 * The first example shows how to put a stored procedure as a query with
 * 2 required parameters.
 * <pre>
 * add-user-query={ call TigAddUserPlainPw(?, ?) }
 * </pre>
 * The same query with plain SQL parameters instead:
 * <pre>
 * add-user-query=insert into users (user_id, password) values (?, ?)
 * </pre>
 *
 * Created: Sat Nov 11 22:22:04 2006
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class TigaseCustomAuth implements UserAuthRepository {

  /**
   * Private logger for class instancess.
   */
  private static final Logger log =
    Logger.getLogger("tigase.db.jdbc.TigaseCustomAuth");

	/**
	 * Query executing periodically to ensure active connection with the database.
	 *
	 * Takes no arguments.
	 */
	public static final String DEF_CONNVALID_KEY = "conn-valid-query";
	/**
	 * Database initialization query which is run after the server is started.
	 *
	 * Takes no arguments.
	 */
	public static final String DEF_INITDB_KEY = "init-db-query";
	/**
	 * Query adding a new user to the database.
	 *
	 * Takes 2 arguments: <code>(user_id (JID), password)</code>
	 */
	public static final String DEF_ADDUSER_KEY = "add-user-query";
	/**
	 * Removes a user from the database.
	 *
	 * Takes 1 argument: <code>(user_id (JID))</code>
	 */
	public static final String DEF_DELUSER_KEY = "del-user-query";
	/**
	 * Rertieves user password from the database for given user_id (JID).
	 *
	 * Takes 1 argument: <code>(user_id (JID))</code>
	 */
	public static final String DEF_GETPASSWORD_KEY = "get-password-query";
	/**
	 * Updates (changes) password for a given user_id (JID).
	 *
	 * Takes 2 arguments: <code>(user_id (JID), password)</code>
	 */
	public static final String DEF_UPDATEPASSWORD_KEY = "update-password-query";
	/**
	 * Performs user login. Normally used when there is a special SP used for this
	 * purpose. This is an alternative way to a method requiring retrieving
	 * user password. Therefore at least one of those queries must be defined:
	 * <code>user-login-query</code> or <code>get-password-query</code>.
	 *
	 * If both quries are defined then <code>user-login-query</code> is used.
	 * Normally this method should be only used with plain text password
	 * authentication or sasl-plain.
	 *
	 * Takes 2 arguments: <code>(user_id (JID), password)</code>
	 */
	public static final String DEF_USERLOGIN_KEY = "user-login-query";
	/**
	 * This query is called when user logs out or disconnects. It can record that
	 * event in the database.
	 *
	 * Takes 1 argument: <code>(user_id (JID))</code>
	 */
	public static final String DEF_USERLOGOUT_KEY = "user-logout-query";
	/**
	 * Comma separated list of NON-SASL authentication mechanisms. Possible mechanisms
	 * are: <code>password</code> and <code>digest</code>. <code>digest</code>
	 * mechanism can work only with <code>get-password-query</code> active and only
	 * when password are stored in plain text format in the database.
	 */
	public static final String DEF_NONSASL_MECHS_KEY = "non-sasl-mechs";
	/**
	 * Comma separated list of SASL authentication mechanisms. Possible mechanisms
	 * are all mechanisms supported by Java implementation. The most common are:
	 * <code>PLAIN</code>, <code>DIGEST-MD5</code>, <code>CRAM-MD5</code>.
	 *
	 * "Non-PLAIN" mechanisms will work only with the <code>get-password-query</code>
	 * active and only when passwords are stored in plain text formay in the database.
	 */
	public static final String DEF_SASL_MECHS_KEY = "sasl-mechs";

	public static final String DEF_CONNVALID_QUERY = "select 1";
	public static final String DEF_INITDB_QUERY = "{ call TigInitdb() }";
	public static final String DEF_ADDUSER_QUERY = "{ call TigAddUserPlainPw(?, ?) }";
	public static final String DEF_DELUSER_QUERY = "{ call TigRemoveUser(?) }";
	public static final String DEF_GETPASSWORD_QUERY = "{ call TigGetPassword(?) }";
	public static final String DEF_UPDATEPASSWORD_QUERY
    = "{ call TigUpdatePasswordPlainPw(?, ?) }";
	public static final String DEF_USERLOGIN_QUERY
    = "{ call TigUserLoginPlainPw(?, ?) }";
	public static final String DEF_USERLOGOUT_QUERY = "{ call TigUserLogout(?) }";

	public static final String DEF_NONSASL_MECHS = "password";
	public static final String DEF_SASL_MECHS = "PLAIN";

	private String convalid_query = DEF_CONNVALID_QUERY;
	private String initdb_query = DEF_INITDB_QUERY;
	private String adduser_query = DEF_ADDUSER_QUERY;
	private String deluser_query = DEF_DELUSER_QUERY;
	private String getpassword_query = DEF_GETPASSWORD_QUERY;
	private String updatepassword_query = DEF_UPDATEPASSWORD_QUERY;
	private String userlogin_query = DEF_USERLOGIN_QUERY;
	private String userlogout_query = DEF_USERLOGOUT_QUERY;

	private String[] nonsasl_mechs = DEF_NONSASL_MECHS.split(",");
	private String[] sasl_mechs = DEF_SASL_MECHS.split(",");

	/**
	 * Database connection string.
	 */
	private String db_conn = null;
	/**
	 * Database active connection.
	 */
	private Connection conn = null;
	private PreparedStatement init_db = null;
	private PreparedStatement add_user = null;
	private PreparedStatement remove_user = null;
	private PreparedStatement get_pass = null;
	private PreparedStatement update_pass = null;
	private PreparedStatement user_login = null;
	private PreparedStatement user_logout = null;
	/**
	 * Prepared statement for testing whether database connection is still
	 * working. If not connection to database is recreated.
	 */
	private PreparedStatement conn_valid_st = null;

	/**
	 * Connection validation helper.
	 */
	private long lastConnectionValidated = 0;
	/**
	 * Connection validation helper.
	 */
	private long connectionValidateInterval = 1000*60;
	private boolean online_status = false;

	/**
	 * <code>initPreparedStatements</code> method initializes internal
	 * database connection variables such as prepared statements.
	 *
	 * @exception SQLException if an error occurs on database query.
	 */
	private void initPreparedStatements() throws SQLException {
		String query = query = convalid_query;
		conn_valid_st = conn.prepareStatement(query);

		query = initdb_query;
		init_db = conn.prepareCall(query);

		query = adduser_query;
		add_user = conn.prepareCall(query);

		query = deluser_query;
		remove_user = conn.prepareCall(query);

		query = getpassword_query;
		get_pass = conn.prepareCall(query);

		query = updatepassword_query;
		update_pass = conn.prepareCall(query);

		query = userlogin_query;
		user_login = conn.prepareCall(query);

		query = userlogout_query;
		user_logout = conn.prepareCall(query);
	}

	/**
	 * <code>checkConnection</code> method checks database connection before any
	 * query. For some database servers (or JDBC drivers) it happens the connection
	 * is dropped if not in use for a long time or after certain timeout passes.
	 * This method allows us to detect the problem and reinitialize database
	 * connection.
	 *
	 * @return a <code>boolean</code> value if the database connection is working.
	 * @exception SQLException if an error occurs on database query.
	 */
	private boolean checkConnection() throws SQLException {
		try {
			synchronized (conn_valid_st) {
				long tmp = System.currentTimeMillis();
				if ((tmp - lastConnectionValidated) >= connectionValidateInterval) {
					conn_valid_st.executeQuery();
					lastConnectionValidated = tmp;
				} // end of if ()
			}
		} catch (Exception e) {
			initRepo();
		} // end of try-catch
		return true;
	}

	private void release(Statement stmt, ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException sqlEx) { }
		}
		if (stmt != null) {
			try {
				stmt.close();
			} catch (SQLException sqlEx) { }
		}
	}

	private String getPassword(final String user)
		throws SQLException, UserNotFoundException {
		ResultSet rs = null;
		try {
			checkConnection();
			synchronized (get_pass) {
				get_pass.setString(1, JIDUtils.getNodeID(user));
				rs = get_pass.executeQuery();
				if (rs.next()) {
					return rs.getString(1);
				} else {
					throw new UserNotFoundException("User does not exist: " + user);
				} // end of if (isnext) else
			}
		} finally {
			release(null, rs);
		}
	}

	// Implementation of tigase.db.UserAuthRepository

	/**
	 * Describe <code>queryAuth</code> method here.
	 *
	 * @param authProps a <code>Map</code> value
	 */
	public void queryAuth(final Map<String, Object> authProps) {
		String protocol = (String)authProps.get(PROTOCOL_KEY);
		if (protocol.equals(PROTOCOL_VAL_NONSASL)) {
			authProps.put(RESULT_KEY, nonsasl_mechs);
		} // end of if (protocol.equals(PROTOCOL_VAL_NONSASL))
		if (protocol.equals(PROTOCOL_VAL_SASL)) {
			authProps.put(RESULT_KEY, sasl_mechs);
		} // end of if (protocol.equals(PROTOCOL_VAL_NONSASL))
	}

	/**
	 * <code>initRepo</code> method initializes database connection
	 * and data repository.
	 *
	 * @exception SQLException if an error occurs on database query.
	 */
	private void initRepo() throws SQLException {
		synchronized (db_conn) {
			conn = DriverManager.getConnection(db_conn);
			initPreparedStatements();
		}
	}

	private String getParamWithDef(Map<String, String> params, String key, String def) {
		if (params == null) {
			return def;
		}
		String result = params.get(key);
		return result != null ? result.trim() : def;
	}

	/**
	 * Describe <code>initRepository</code> method here.
	 *
	 * @param connection_str a <code>String</code> value
	 * @exception DBInitException if an error occurs
	 */
	public void initRepository(final String connection_str,
		Map<String, String> params) throws DBInitException {
		db_conn = connection_str;
		convalid_query = getParamWithDef(params, DEF_CONNVALID_KEY, DEF_CONNVALID_QUERY);
		initdb_query = getParamWithDef(params, DEF_INITDB_KEY, DEF_INITDB_QUERY);
		adduser_query = getParamWithDef(params, DEF_ADDUSER_KEY, DEF_ADDUSER_QUERY);
		deluser_query = getParamWithDef(params, DEF_DELUSER_KEY, DEF_DELUSER_QUERY);
		getpassword_query = getParamWithDef(params, DEF_GETPASSWORD_KEY,
			DEF_GETPASSWORD_QUERY);
		updatepassword_query = getParamWithDef(params, DEF_UPDATEPASSWORD_KEY,
			DEF_UPDATEPASSWORD_QUERY);
		userlogin_query = getParamWithDef(params, DEF_USERLOGIN_KEY,
			DEF_USERLOGIN_QUERY);
		userlogout_query = getParamWithDef(params, DEF_USERLOGOUT_KEY,
			DEF_USERLOGOUT_QUERY);

		nonsasl_mechs = getParamWithDef(params, DEF_NONSASL_MECHS_KEY,
			DEF_NONSASL_MECHS).split(",");
		sasl_mechs = getParamWithDef(params, DEF_SASL_MECHS_KEY,
			DEF_SASL_MECHS).split(",");
		try {
			initRepo();
			if (params != null && params.get("init-db") != null) {
				init_db.executeQuery();
			}
		} catch (SQLException e) {
			conn = null;
			throw	new DBInitException("Problem initializing jdbc connection: "
				+ db_conn, e);
		}
	}

	public String getResourceUri() { return db_conn; }

	/**
	 * Describe <code>plainAuth</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @param password a <code>String</code> value
	 * @return a <code>boolean</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 */
	public boolean plainAuth(final String user, final String password)
		throws UserNotFoundException, TigaseDBException, AuthorizationException {
		ResultSet rs = null;
		try {
			checkConnection();
			synchronized (user_login) {
				String user_id = JIDUtils.getNodeID(user);
				user_login.setString(1, user_id);
				user_login.setString(2, password);
				rs = user_login.executeQuery();
				if (rs.next()) {
					boolean auth_result_ok = user_id.equals(rs.getString(1));
					if (auth_result_ok) {
						return true;
					} else {
						log.fine("Login failed, for user: '" + user_id + "'"
							+ ", password: '" + password + "'"
							+ ", from DB got: " + rs.getString(1));
					}
				}
				throw new UserNotFoundException("User does not exist: " + user);
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Problem accessing repository.", e);
		} // end of catch
	}

	/**
	 * Describe <code>digestAuth</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @param digest a <code>String</code> value
	 * @param id a <code>String</code> value
	 * @param alg a <code>String</code> value
	 * @return a <code>boolean</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 * @exception AuthorizationException if an error occurs
	 */
	public boolean digestAuth(final String user, final String digest,
		final String id, final String alg)
		throws UserNotFoundException, TigaseDBException, AuthorizationException {
		throw new AuthorizationException("Not supported.");
	}

	/**
	 * Describe <code>otherAuth</code> method here.
	 *
	 * @param props a <code>Map</code> value
	 * @return a <code>boolean</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 * @exception AuthorizationException if an error occurs
	 */
	public boolean otherAuth(final Map<String, Object> props)
		throws UserNotFoundException, TigaseDBException, AuthorizationException {
		String proto = (String)props.get(PROTOCOL_KEY);
		if (proto.equals(PROTOCOL_VAL_SASL)) {
			String mech = (String)props.get(MACHANISM_KEY);
			if (mech.equals("PLAIN")) {
				return saslAuth(props);
			} // end of if (mech.equals("PLAIN"))
			throw new AuthorizationException("Mechanism is not supported: " + mech);
		} // end of if (proto.equals(PROTOCOL_VAL_SASL))
		throw new AuthorizationException("Protocol is not supported: " + proto);
	}

	public void logout(final String user)
		throws UserNotFoundException, TigaseDBException {
		try {
			checkConnection();
			synchronized (user_logout) {
				user_logout.setString(1, JIDUtils.getNodeID(user));
				user_logout.execute();
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Problem accessing repository.", e);
		}
	}

	/**
	 * Describe <code>addUser</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @param password a <code>String</code> value
	 * @exception UserExistsException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 */
	public void addUser(final String user, final String password)
		throws UserExistsException, TigaseDBException {
		try {
			checkConnection();
			synchronized (add_user) {
				add_user.setString(1, JIDUtils.getNodeID(user));
				add_user.setString(2, password);
				add_user.execute();
			}
		} catch (SQLIntegrityConstraintViolationException e) {
			throw new UserExistsException("Error while adding user to repository, user exists?", e);
		} catch (SQLException e) {
			throw new TigaseDBException("Problem accessing repository.", e);
		}
	}

	/**
	 * Describe <code>updatePassword</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @param password a <code>String</code> value
	 * @exception UserExistsException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 */
	public void updatePassword(final String user, final String password)
		throws UserNotFoundException, TigaseDBException {
		try {
			checkConnection();
			synchronized (update_pass) {
				update_pass.setString(1, JIDUtils.getNodeID(user));
				update_pass.setString(2, password);
				update_pass.execute();
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Problem accessing repository.", e);
		}
	}

	/**
	 * Describe <code>removeUser</code> method here.
	 *
	 * @param user a <code>String</code> value
	 * @exception UserNotFoundException if an error occurs
	 * @exception TigaseDBException if an error occurs
	 */
	public void removeUser(final String user)
		throws UserNotFoundException, TigaseDBException {
		try {
			checkConnection();
			synchronized (remove_user) {
				remove_user.setString(1, JIDUtils.getNodeID(user));
				remove_user.execute();
			}
		} catch (SQLException e) {
			throw new TigaseDBException("Problem accessing repository.", e);
		}
	}

	private String decodeString(byte[] source, int start_from) {
		int idx = start_from;
		while (source[idx] != 0 && idx < source.length)	{ ++idx;	}
		return new String(source, start_from, idx - start_from);
	}

	private boolean saslAuth(final Map<String, Object> props)
		throws UserNotFoundException, TigaseDBException, AuthorizationException {
		String data_str = (String)props.get(DATA_KEY);
		String domain = (String)props.get(REALM_KEY);
		props.put(RESULT_KEY, null);
		byte[] in_data = (data_str != null ? Base64.decode(data_str) : new byte[0]);

		int auth_idx = 0;
		while (in_data[auth_idx] != 0 && auth_idx < in_data.length)
		{ ++auth_idx;	}
		String authoriz = new String(in_data, 0, auth_idx);
		int user_idx = ++auth_idx;
		while (in_data[user_idx] != 0 && user_idx < in_data.length)
		{ ++user_idx;	}
		String user_name = new String(in_data, auth_idx, user_idx - auth_idx);
		++user_idx;
		String jid = user_name;
		if (JIDUtils.getNodeNick(user_name) == null) {
			jid = JIDUtils.getNodeID(user_name, domain);
		}
		props.put(USER_ID_KEY, jid);
		String passwd =	new String(in_data, user_idx, in_data.length - user_idx);
		return plainAuth(jid, passwd);
	}

} // TigaseCustomAuth
