/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2012 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author:$
 * $Date$
 *
 */
package tigase.server.xmppclient;

//~--- non-JDK imports --------------------------------------------------------

import tigase.util.TigaseStringprepException;
import tigase.xmpp.BareJID;

import tigase.db.DBInitException;
import tigase.db.DataRepository;
import tigase.db.RepositoryFactory;
import tigase.db.UserNotFoundException;

//~--- JDK imports ------------------------------------------------------------

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;

//~--- classes ----------------------------------------------------------------

/**
 * Extended implementation of SeeOtherHost using redirect information from
 * database
 * 
 */
public class SeeOtherHostDB extends SeeOtherHostHashed {

	private static final Logger log = Logger.getLogger(SeeOtherHostDB.class.getName());

	public static final String SEE_OTHER_HOST_TABLE = "tig_see_other_hosts";
	public static final String SEE_OTHER_HOST_DB_URL_KEY = CM_SEE_OTHER_HOST_CLASS_PROP_KEY
			+ "/" + "db-url";
	public static final String SEE_OTHER_HOST_DB_QUERY_KEY =
			CM_SEE_OTHER_HOST_CLASS_PROP_KEY + "/" + "get-host-query";
	public static final String DB_GET_ALL_DATA_DB_QUERY_KEY =
			CM_SEE_OTHER_HOST_CLASS_PROP_KEY + "/" + "get-all-data-query";
	public static final String GET_ALL_QUERY_TIMEOUT_QUERY_KEY =
		CM_SEE_OTHER_HOST_CLASS_PROP_KEY + "/" + "get-all-query-timeout";

	
	public static final String SERIAL_ID = "sid";
	public static final String USER_ID = "uid";
	public static final String NODE_ID = "node_id";

	public static final String DEF_DB_GET_HOST_QUERY = " select * from tig_users, "
			+ SEE_OTHER_HOST_TABLE + " where tig_users.uid = " + SEE_OTHER_HOST_TABLE + "."
			+ USER_ID + " and user_id = ?";

	private static final String DEF_DB_GET_ALL_DATA_QUERY =
			"select user_id, node_id from tig_users, " + SEE_OTHER_HOST_TABLE
					+ " where tig_users.uid = " + SEE_OTHER_HOST_TABLE + "." + USER_ID;

	private static final String CREATE_STATS_TABLE = "create table " + SEE_OTHER_HOST_TABLE
			+ " ( " + SERIAL_ID + " serial," + USER_ID + " bigint unsigned NOT NULL, "
			+ NODE_ID + " varchar(2049) NOT NULL, " + " primary key (" + SERIAL_ID + "), "
			+ " constraint tig_see_other_host_constr foreign key (" + USER_ID
			+ ") references tig_users (" + USER_ID + ")" + ")";
	private static final int DEF_QUERY_TIME_OUT = 0;

	private String get_host_query = DEF_DB_GET_HOST_QUERY;
	private String get_all_data_query = DEF_DB_GET_ALL_DATA_QUERY;
	private String get_host_DB_url = "";

	private DataRepository data_repo = null;
	private Map<BareJID, BareJID> redirectsMap =
			new ConcurrentSkipListMap<BareJID, BareJID>();

	// Methods

	@Override
	public BareJID findHostForJID(BareJID jid, BareJID host) {

		
		
		BareJID see_other_host = redirectsMap.get(jid);
		if (see_other_host != null) {
			return see_other_host;
		} else {
			see_other_host = host;
		}

		try {
			see_other_host = queryDB(jid);
		} catch (Exception ex) {
			see_other_host = super.findHostForJID(jid, host);
			log.log(Level.SEVERE, "DB lookup failed, fallback to SeeOtherHostHashed: ", ex);
		}

		return see_other_host;
	}

	@Override
	public void getDefaults(Map<String, Object> defs, Map<String, Object> params) {
		super.getDefaults(defs, params);

		if (params.containsKey("--user-db-uri")) {
			get_host_DB_url = (String) params.get("--user-db-uri");
		}
		defs.put(SEE_OTHER_HOST_DB_URL_KEY, get_host_DB_url);
		defs.put(SEE_OTHER_HOST_DB_QUERY_KEY, get_host_query);
		defs.put(DB_GET_ALL_DATA_DB_QUERY_KEY, get_all_data_query);
	}

	@Override
	public void setProperties(Map<String, Object> props) {
		super.setProperties(props);

		if ((props.containsKey(SEE_OTHER_HOST_DB_URL_KEY))
				&& !props.get(SEE_OTHER_HOST_DB_URL_KEY).toString().trim().isEmpty()) {
			get_host_DB_url = props.get(SEE_OTHER_HOST_DB_URL_KEY).toString().trim();
		}
		props.put(SEE_OTHER_HOST_DB_URL_KEY, get_host_DB_url);

		if ((props.containsKey(SEE_OTHER_HOST_DB_QUERY_KEY))
				&& !props.get(SEE_OTHER_HOST_DB_QUERY_KEY).toString().trim().isEmpty()) {
			get_host_query = props.get(SEE_OTHER_HOST_DB_QUERY_KEY).toString().trim();
		}
		props.put(SEE_OTHER_HOST_DB_QUERY_KEY, get_host_query);

		if ((props.containsKey(DB_GET_ALL_DATA_DB_QUERY_KEY))
				&& !props.get(DB_GET_ALL_DATA_DB_QUERY_KEY).toString().trim().isEmpty()) {
			get_all_data_query = props.get(DB_GET_ALL_DATA_DB_QUERY_KEY).toString().trim();
		}
		props.put(DB_GET_ALL_DATA_DB_QUERY_KEY, get_all_data_query);

		try {
			initRepository(get_host_DB_url, null);
		} catch (Exception ex) {
			log.log(Level.SEVERE, "Cannot initialize connection to database: ", ex);
		}
	}

	public void initRepository(String conn_str, Map<String, String> map)
			throws SQLException, ClassNotFoundException, IllegalAccessException,
			InstantiationException, DBInitException {

		log.log(Level.INFO, "Initializing dbAccess for db connection url: {0}", conn_str);
		data_repo = RepositoryFactory.getDataRepository(null, conn_str, map);

		// checkDB();

		data_repo.initPreparedStatement(get_host_query, get_host_query);
		data_repo.initPreparedStatement(get_all_data_query, get_all_data_query);
		queryAllDB();
	}

	// private void checkDB() throws SQLException {
	// data_repo.checkTable(SEE_OTHER_HOST_TABLE, CREATE_STATS_TABLE);
	// }

	private BareJID queryDB(BareJID user) throws UserNotFoundException, SQLException,
			TigaseStringprepException {

		PreparedStatement get_host = data_repo.getPreparedStatement(user, get_host_query);

		ResultSet rs = null;

		synchronized (get_host) {
			get_host.setString(1, user.toString());

			rs = get_host.executeQuery();

			if (rs.next()) {
				return BareJID.bareJIDInstance(rs.getString(NODE_ID));
			} else {
				throw new UserNotFoundException("Item does not exist for user: " + user);
			} // end of if (isnext) else
		}
	}

	private void queryAllDB() throws SQLException {

		PreparedStatement get_all = data_repo.getPreparedStatement(null, get_all_data_query);
		get_all.setQueryTimeout(DEF_QUERY_TIME_OUT);

		ResultSet rs = null;

		synchronized (get_all) {

			rs = get_all.executeQuery();

			while (rs.next()) {
				String user_jid = rs.getString("user_id");
				String node_jid = rs.getString(NODE_ID);
				try {
					BareJID user = BareJID.bareJIDInstance(user_jid);
					BareJID node = BareJID.bareJIDInstance(node_jid);
					redirectsMap.put(user, node);
				} catch (TigaseStringprepException ex) {
					log.warning("Invalid user's or node's JID: " + user_jid + ", " + node_jid);
				}
			} // end of if (isnext) else
		}
		
		log.info("Loaded " + redirectsMap.size() + " redirect definitions from database.");
	}
}
