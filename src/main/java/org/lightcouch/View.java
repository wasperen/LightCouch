/*
 * Copyright (C) 2011 Ahmed Yehia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lightcouch;

import static org.lightcouch.CouchDbUtil.*;
import static org.lightcouch.URIBuilder.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * <p>This class allows construction and sending of View query requests.
 * The API supports view queries for various data type results, and for pagination.
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * {@code
 *  List<Foo> list = dbClient.view("example/foo")
 *	.includeDocs(true).startKey("start-key").endKey("end-key").limit(10).query(Foo.class);
 *  
 *  int count = dbClient.view("example/by_tag").queryForInt(); // query for scalar values
 *  
 *  ViewResult<int[], String, Foo> result =  dbClient.view() // query for the view entries
 * 	.viewName("example/by_date")
 * 	.key(2011, 10, 15) // complex key 
 * 	.reduce(false)
 * 	.includeDocs(true)
 * 	.queryView(int[].class, String.class, Foo.class);
 * 
 *  // pagination
 *  Page<Foo> page = dbClient.view("example/foo").queryPage(15, param, Foo.class);
 *  // page.get*Param() contains the param to query subsequent pages
 * }
 * </pre>
 * 
 * @author Ahmed Yehia
 */
public class View {
	private static final Log log = LogFactory.getLog(View.class);
	
	// ------------------- constants for defining paging param
	private static final String START_KEY                = "s_k";
	private static final String START_KEY_DOC_ID         = "s_k_d_i";
	private static final String CURRENT_START_KEY        = "c_k";
	private static final String CURRENT_START_KEY_DOC_ID = "c_k_d_i";
	private static final String CURRENT_KEYS             = "c";
	private static final String ACTION                   = "a";
	private static final String NEXT                     = "n";
	private static final String PREVIOUS                 = "p";
	
	// ---------------------------------------------- Fields
	private String key;
	private String startKey;
	private String startKeyDocId;
	private String endKey;
	private String endKeyDocId;
	private Integer limit;
	private String stale;
	private Boolean descending;
	private Integer skip;
	private Boolean group;
	private Integer groupLevel;
	private Boolean reduce;
	private Boolean includeDocs;
	private Boolean inclusiveEnd;
	private Boolean updateSeq;
	
	private String viewId;
	private CouchDbClient dbc;
	private Gson gson;
	
	View(CouchDbClient dbc, String viewId) {
		this.dbc = dbc;
		this.gson = dbc.getGson();
		try {
			String[] v = viewId.split("/");
			this.viewId = String.format("_design/%s/_view/%s", v[0], v[1]);
		} catch (Exception e) {
			String msg = "Invalid View URI. Expecting a format: design_doc_name/view_name";
			log.warn(msg);
			throw new IllegalArgumentException(msg);
		}
	}
	
	// ----------------------------------------------- Query options
	
	/**
	 * Queries a view as an {@link InputStream}
	 * <p>The stream should be properly closed after usage, as to avoid connection leaks.
	 * @return The result as an {@link InputStream}.
	 */
	public InputStream queryForStream() {
		String viewParams = getViewParams();
		URI uri = builder(dbc.getDBUri()).path(viewId).query(viewParams).build();
		return dbc.get(uri);
	}
	
	/**
	 * Queries a view.
	 * @param <T> Object type T
	 * @param classOfT The class of type T
	 * @return The result of the view query as a {@code List<T> }
	 */
	public <T> List<T> query(Class<T> classOfT) {
		InputStream instream = null;
		try {  
			Reader reader = new InputStreamReader(instream = queryForStream());
			JsonArray jsonArray = new JsonParser().parse(reader)
					.getAsJsonObject().getAsJsonArray("rows");
			List<T> list = new ArrayList<T>();
			for (JsonElement jsonElem : jsonArray) {
				JsonElement elem = jsonElem.getAsJsonObject();
				if(Boolean.TRUE.equals(this.includeDocs)) {
					elem = jsonElem.getAsJsonObject().get("doc");
				}
				T t = this.gson.fromJson(elem, classOfT);
				list.add(t);
			}
			return list;
		} finally {
			close(instream);
		}
	}

	/**
	 * Queries a view.
	 * @param <K> Object type K (key)
	 * @param <V> Object type V (value)
	 * @param classOfK The class of type K.
	 * @param classOfV The class of type V.
	 * @param classOfT The class of type T.
	 * @return The View result entries.
	 */
	public <K, V, T> ViewResult<K, V, T> queryView(Class<K> classOfK, Class<V> classOfV, Class<T> classOfT) {
		InputStream instream = null;
		try {  
			Reader reader = new InputStreamReader(instream = queryForStream());
			JsonObject json = new JsonParser().parse(reader).getAsJsonObject(); 
			ViewResult<K, V, T> vr = new ViewResult<K, V, T>();
			vr.setTotalRows(getElementAsLong(json, "total_rows")); 
			vr.setOffset(getElementAsInt(json, "offset"));
			vr.setUpdateSeq(getElementAsLong(json, "update_seq"));
			JsonArray jsonArray = json.getAsJsonArray("rows");
			if(jsonArray.size() == 0) { // validate available rows
				throw new NoDocumentException("No result was returned by this view query.");
			}
			for (JsonElement e : jsonArray) {
				ViewResult<K, V, T>.Rows row = vr.new Rows();
				row.setId(JsonToObject(gson, e, "id", String.class));
				row.setKey(JsonToObject(gson, e, "key", classOfK));
				row.setValue(JsonToObject(gson, e, "value", classOfV));
				if(includeDocs == Boolean.TRUE) {
					row.setDoc(JsonToObject(gson, e, "doc", classOfT));
				}
				vr.getRows().add(row);
			}
			return vr;
		} finally {
			close(instream);
		}
	}
	
	/**
	 * @return The result of the view as String.
	 */
	public String queryForString() {
		return queryValue(String.class);
	}
	
	/**
	 * @return The result of the view as int.
	 */
	public int queryForInt() {
		return queryValue(int.class);
	}
	
	/**
	 * @return The result of the view as long.
	 */
	public long queryForLong() {
		return queryValue(long.class);
	}
	
	/**
	 * @return The result of the view as boolean.
	 */
	public boolean queryForBoolean() {
		return queryValue(boolean.class);
	}
	
	/**
	 * Queries for scalar values. Internal use.
	 */
	private <V> V queryValue(Class<V> classOfV) {
		InputStream instream = null;
		try {  
			Reader reader = new InputStreamReader(instream = queryForStream());
			JsonArray array = new JsonParser().parse(reader).
							getAsJsonObject().get("rows").getAsJsonArray();
			if(array.size() != 1) { // expect exactly 1 row
				throw new NoDocumentException("Expecting exactly a single result of this view query, but was: " + array.size());
			}
			return JsonToObject(gson, array.get(0), "value", classOfV);
		} finally {
			close(instream);
		}
	}
	
	/**
	 * Queries a view for pagination, returns a next or a previous page, this method
	 * figures out which page to return based on the given param that is generated by an
	 * earlier call to this method, quering the first page is done by passing a {@code null} param.
	 * @param <T> Object type T
	 * @param rowsPerPage The number of rows per page.
	 * @param param The request parameter to use to query a page, or {@code null} to return the first page.
	 * @param classOfT The class of type T.
	 * @return {@link Page}
	 */
	public <T> Page<T> queryPage(int rowsPerPage, String param, Class<T> classOfT) {
		if(param == null) { // assume first page
			return queryNextPage(rowsPerPage, null, null, null, null, classOfT);
		}
		String currentStartKey;
		String currentStartKeyDocId;
		String startKey;
		String startKeyDocId;
		String action;
		try {
			// extract fields from the returned HEXed JSON object
			JsonObject json = new JsonParser().parse(new String(Hex.decodeHex(param.toCharArray()))).getAsJsonObject();
			if(log.isDebugEnabled()) {
				log.debug("Paging Param Decoded = " + json);
			}
			JsonObject jsonCurrent = json.getAsJsonObject(CURRENT_KEYS);
			currentStartKey = jsonCurrent.get(CURRENT_START_KEY).getAsString();
			currentStartKeyDocId = jsonCurrent.get(CURRENT_START_KEY_DOC_ID).getAsString();
			startKey = json.get(START_KEY).getAsString();
			startKeyDocId = json.get(START_KEY_DOC_ID).getAsString();
			action = json.get(ACTION).getAsString();
		} catch (Exception e) {
			throw new CouchDbException("could not parse the given param!", e);
		}
		if(PREVIOUS.equals(action)) { // previous
			return queryPreviousPage(rowsPerPage, currentStartKey, currentStartKeyDocId, startKey, startKeyDocId, classOfT);
		} else { // next
			return queryNextPage(rowsPerPage, currentStartKey, currentStartKeyDocId, startKey, startKeyDocId, classOfT);
		}
	}
	
	/**
	 * @return The next page.
	 */
	private <T> Page<T> queryNextPage(int rowsPerPage, String currentStartKey, 
			String currentStartKeyDocId, String startKey, String startKeyDocId, Class<T> classOfT) {
		// set view query params
		limit(rowsPerPage + 1);
		includeDocs(true);
		reduce(false);
		if(startKey != null) { 
			startKey(startKey);
			startKeyDocId(startKeyDocId);
		}
		// init page, query view
		Page<T> page = new Page<T>();
		List<T> pageList = new ArrayList<T>();
		ViewResult<String, String, T> vr = queryView(String.class, String.class, classOfT);
		List<ViewResult<String, String, T>.Rows> rows = vr.getRows();
		int resultRows = rows.size();
		int offset = vr.getOffset();
		long totalRows = vr.getTotalRows();
		// holds page params
		JsonObject currentKeys = new JsonObject();
		JsonObject jsonNext = new JsonObject();
		JsonObject jsonPrev = new JsonObject();
		currentKeys.addProperty(CURRENT_START_KEY, rows.get(0).getKey());
		currentKeys.addProperty(CURRENT_START_KEY_DOC_ID, rows.get(0).getId());
		for (int i = 0; i < resultRows; i++) {
			// set keys for the next page
			if (i == resultRows - 1) { // last element (i.e rowsPerPage + 1)
				if(resultRows > rowsPerPage) { // if not last page
					page.setHasNext(true);
					jsonNext.addProperty(START_KEY, rows.get(i).getKey());
					jsonNext.addProperty(START_KEY_DOC_ID, rows.get(i).getId());
					jsonNext.add(CURRENT_KEYS, currentKeys);
					jsonNext.addProperty(ACTION, NEXT); 
					page.setNextParam(Hex.encodeHexString(jsonNext.toString().getBytes()));
					continue; // exclude 
				} 
			}
			pageList.add(rows.get(i).getDoc());
		}
		// set keys for the previous page
		if(offset != 0) { // if not first page
			page.setHasPrevious(true);
			jsonPrev.addProperty(START_KEY, currentStartKey);
			jsonPrev.addProperty(START_KEY_DOC_ID, currentStartKeyDocId);
			jsonPrev.add(CURRENT_KEYS, currentKeys);
			jsonPrev.addProperty(ACTION, PREVIOUS); 
			page.setPreviousParam(Hex.encodeHexString(jsonPrev.toString().getBytes()));
		}
		// calculate paging display info
		page.setResultList(pageList);
		page.setTotalResults(totalRows);
		page.setResultFrom(offset + 1);
		int resultTo = rowsPerPage > resultRows ? resultRows : rowsPerPage; // fix when rowsPerPage exceeds returned rows
		page.setResultTo(offset + resultTo);
		page.setPageNumber((int) Math.ceil(page.getResultFrom() / Double.valueOf(rowsPerPage)));
		return page;
	}
	
	/**
	 * @return The previous page.
	 */
	private <T> Page<T> queryPreviousPage(int rowsPerPage, String currentStartKey, 
			String currentStartKeyDocId, String startKey, String startKeyDocId, Class<T> classOfT) {
		// set view query params
		limit(rowsPerPage + 1);
		includeDocs(true);
		reduce(false);
		descending(true); // read backward
		startKey(currentStartKey); 
		startKeyDocId(currentStartKeyDocId); 
		// init page, query view
		Page<T> page = new Page<T>();
		List<T> pageList = new ArrayList<T>();
		ViewResult<String, String, T> vr = queryView(String.class, String.class, classOfT);
		List<ViewResult<String, String, T>.Rows> rows = vr.getRows();
		int resultRows = rows.size();
		int offset = vr.getOffset();
		long totalRows = vr.getTotalRows();
		Collections.reverse(rows); // fix order
		// holds page params
		JsonObject currentKeys = new JsonObject();
		JsonObject jsonNext = new JsonObject();
		JsonObject jsonPrev = new JsonObject();
		currentKeys.addProperty(CURRENT_START_KEY, rows.get(0).getKey());
		currentKeys.addProperty(CURRENT_START_KEY_DOC_ID, rows.get(0).getId());
		for (int i = 0; i < resultRows; i++) {
			// set keys for the next page
			if (i == resultRows - 1) { // last element (i.e rowsPerPage + 1)
				if(resultRows >= rowsPerPage) { // if not last page
					page.setHasNext(true);
					jsonNext.addProperty(START_KEY, rows.get(i).getKey());
					jsonNext.addProperty(START_KEY_DOC_ID, rows.get(i).getId());
					jsonNext.add(CURRENT_KEYS, currentKeys);
					jsonNext.addProperty(ACTION, NEXT); 
					page.setNextParam(Hex.encodeHexString(jsonNext.toString().getBytes()));
					continue; 
				}
			}
			pageList.add(rows.get(i).getDoc());
		}
		// set keys for the previous page
		if(offset != (totalRows - rowsPerPage - 1)) { // if not first page
			page.setHasPrevious(true);
			jsonPrev.addProperty(START_KEY, currentStartKey);
			jsonPrev.addProperty(START_KEY_DOC_ID, currentStartKeyDocId);
			jsonPrev.add(CURRENT_KEYS, currentKeys);
			jsonPrev.addProperty(ACTION, PREVIOUS); 
			page.setPreviousParam(Hex.encodeHexString(jsonPrev.toString().getBytes()));
		}
		// calculate paging display info
		page.setResultList(pageList);
		page.setTotalResults(totalRows);
		page.setResultFrom((int) totalRows - (offset + rowsPerPage));
		int resultTo = (int) totalRows - offset - 1;
		page.setResultTo(resultTo);
		page.setPageNumber(resultTo / rowsPerPage);
		return page;
	}
	
	// -------------------------------------------------------- Parameters setter
	
	/**
	 * @param key The key value, accepts a single value or multiple values for complex keys.
	 */
	public View key(Object... key) {
		this.key = getKeyAsJson(key);
		return this;
	}
	
	/**
	 * @param startKey The start key value, accepts a single value or multiple values for complex keys.
	 */
	public View startKey(Object... startKey) {
		this.startKey = getKeyAsJson(startKey);
		return this;
	}
	
	public View startKeyDocId(String startKeyDocId) {
		this.startKeyDocId = startKeyDocId;
		return this;
	}
	
	/**
	 * @param endKey The end key value, accepts a single value or multiple values for complex keys.
	 */
	public View endKey(Object... endKey) {
		this.endKey = getKeyAsJson(endKey);
		return this;
	}
	
	public View endKeyDocId(String endKeyDocId) {
		this.endKeyDocId = endKeyDocId;
		return this;
	}
	
	public View limit(Integer limit) {
		this.limit = limit;
		return this;
	}
	
	/**
	 * @param stale Accept values: ok | update_after (update_after as of CouchDB 1.1.0)
	 */
	public View stale(String stale) {
		this.stale = stale;
		return this;
	}
	
	/**
	 * Reverses the reading direction, not the sort order.
	 */
	public View descending(Boolean descending) {
		this.descending = Boolean.valueOf(gson.toJson(descending));
		return this;
	}
	
	/**
	 * @param skip Skips <i>n</i> number of documents.
	 */
	public View skip(Integer skip) {
		this.skip = skip;
		return this;
	}
	
	/**
	 * @param group Specifies whether the reduce function reduces the result to a set of keys, 
	 * or to a single result. Defaults to false (single result).
	 */
	public View group(Boolean group) {
		this.group = group;
		return this;
	}
	
	public View groupLevel(Integer groupLevel) {
		this.groupLevel = groupLevel;
		return this;
	}
	
	/**
	 * @param reduce Indicates whether to use the reduce function of the view,
	 * defaults to true if the reduce function is defined.
	 */
	public View reduce(Boolean reduce) {
		this.reduce = reduce;
		return this;
	}
	
	public View includeDocs(Boolean includeDocs) {
		this.includeDocs = includeDocs;
		return this;
	}
	
	/**
	 * @param inclusiveEnd Indicates whether the endkey is included in the result, 
	 * defaults to true.
	 */
	public View inclusiveEnd(Boolean inclusiveEnd) {
		this.inclusiveEnd = inclusiveEnd;
		return this;
	}
	
	public View updateSeq(Boolean updateSeq) {
		this.updateSeq = updateSeq;
		return this;
	}
	
	// --------------------------------------------------- Helper
	/**
	 * @return The view query parameters as a String.
	 */
	private String getViewParams() {
		StringBuilder builder = new StringBuilder();
		setParam(builder, "key", key);
		setParam(builder, "startkey", startKey);
		setParam(builder, "startkey_docid", startKeyDocId);
		setParam(builder, "endkey", endKey);
		setParam(builder, "endkey_docid", endKeyDocId);
		setParam(builder, "limit", limit);
		setParam(builder, "stale", stale);
		setParam(builder, "descending", descending);
		setParam(builder, "skip", skip);
		setParam(builder, "group", group);
		setParam(builder, "group_level", groupLevel);
		setParam(builder, "reduce", reduce);
		setParam(builder, "include_docs", includeDocs);
		setParam(builder, "inclusive_end", inclusiveEnd);
		setParam(builder, "update_seq", updateSeq);
		return builder.toString();
	}
	
	private void setParam(StringBuilder builder, String name, Object value) {
		if(name != null && value != null)
			builder.append(String.format("%s=%s&", name, value));
	}
	
	private String getKeyAsJson(Object... key) {
		return (key.length == 1) ? gson.toJson(key[0]) : gson.toJson(key); // single or complex key
	}
}