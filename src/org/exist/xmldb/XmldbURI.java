/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001 Wolfgang M. Meier
 *  wolfgang@exist-db.org
 *  http://exist.sourceforge.net
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; er version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id:
 */
package org.exist.xmldb;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Pattern;

/** A utility class for xmldb URis.
 * Since, java.net.URI is <strong>final</strong> this class acts as a wrapper.
 * @author Pierrick Brihaye <pierrick.brihaye@free.fr>
 */
public class XmldbURI {
	
	//Should be provided by org.xmldb.api package !!! 
	public static final String XMLDB_URI_PREFIX = "xmldb:";
	
	private URI wrappedURI;	
	private String instanceName;
	private String host;
	private int port = -1; 
	private String context;  	
	private String escapedCollectionName;
	private String apiName;

	/** Contructs an XmldbURI from given string.
	 * Note that we construct URIs starting with XmldbURI.XMLDB_URI_PREFIX.
	 * Do not forget that org.xmldb.api.DatabaseManager <strong>trims</strong> this prefix. 
	 * @param xmldbURI A string 
	 * @throws URISyntaxException If the given string is not a valid xmldb URI.
	 */
	public XmldbURI(String xmldbURI) throws URISyntaxException {
    	try {
    		wrappedURI = new URI(xmldbURI);    		
    		parseURI();
    	} catch (URISyntaxException e) {
        	wrappedURI = null;        	
        	throw e;     	
    	}
	}
	
	/** Contructs an XmldbURI from the given string, handling the necessary escapings.
	 * @param accessURI
	 * @param collectionName An unescaped collection name.
	 * @throws URISyntaxException
	 */
	public XmldbURI(String accessURI, String collectionName) throws URISyntaxException {
    	try {
    		String escaped = URLEncoder.encode(collectionName, "UTF-8");    		
    		//This is the trick : unescape slashed in order to keep java.net.URI capabilities 
    		escaped = escaped.replaceAll("%2F", "/");
    		escaped = escaped.replaceAll("%23", "#");
    		escaped = escaped.replaceAll("%3F", "?");
    		wrappedURI = new URI(accessURI + escaped);    		
    		parseURI();
    	} catch (URISyntaxException e) {
        	wrappedURI = null;        	
        	throw e;     	
    	} catch (UnsupportedEncodingException e) {
        	wrappedURI = null;        	
        	throw new URISyntaxException(accessURI + collectionName, e.getMessage());  	
    	}
	}
	
	/** Feeds private members
	 * @throws URISyntaxException
	 */
	private void parseURI() throws URISyntaxException {	
		String path = null;	
		URI truncatedURI;
		//Reinitialise members
		this.instanceName = null;
		this.host = null;
		this.port = -1;
		this.apiName = null;
		if (wrappedURI.getScheme() == null) { 			
			path = wrappedURI.getPath();			
		}
		else
		{			
			if (!wrappedURI.toString().startsWith(XMLDB_URI_PREFIX))
				throw new URISyntaxException(wrappedURI.toString(), "xmldb URI scheme does not start with " + XMLDB_URI_PREFIX);
			try {
				truncatedURI = new URI(wrappedURI.toString().substring(XMLDB_URI_PREFIX.length()));
			} catch (URISyntaxException e) {
				//Put the "right" URI in the message ;-)
				throw new URISyntaxException(wrappedURI.toString(), e.getMessage());				
			}
	    	if (truncatedURI.getQuery() != null)
	    		//Put the "right" URI in the message ;-)
	    		throw new URISyntaxException(wrappedURI.toString(), "xmldb URI should not provide a query part");
	    	if (truncatedURI.getFragment() != null)
	    		//Put the "right" URI in the message ;-)    		
	    		throw new URISyntaxException(wrappedURI.toString(), "xmldb URI should not provide a fragment part");
	    	//Is an encoded scheme ever possible ?
	    	instanceName = truncatedURI.getScheme();
			if (instanceName == null)   
				//Put the "right" URI in the message ;-)
				throw new URISyntaxException(wrappedURI.toString().toString(), "xmldb URI scheme has no instance name");			
			host = truncatedURI.getHost();
			port = truncatedURI.getPort();
			path = truncatedURI.getPath();			
		}
		splitPath(path);
	}
	
	/** Given a java.net.URI.getPath(), <strong>tries</strong> to dispatch the host's context
	 * from the collection name as smartly as possible. 
	 * One would probably prefer a split policy based on the presence of a well-known root collection.
	 * @param path The java.net.URI.getPath() provided.
	 * @throws URISyntaxException
	 */
	private void splitPath(String path) throws URISyntaxException {
		int index = -1;
		int lastIndex = -1;	
		//Reinitialise members
		this.context = null;
		this.escapedCollectionName = null;
		if (path != null) {				
			if (host != null) {  
	    		//TODO : use named constants  
	        	index = path.lastIndexOf("/xmlrpc");        	         	
	        	if (index > lastIndex) {
	        		apiName = "xmlrpc";        		
	        		escapedCollectionName = path.substring(index + "/xmlrpc".length());
	        		context = path.substring(0, index) + "/xmlrpc";
	        		lastIndex = index;
	        	}         	
	        	//TODO : use named constants  
	        	index = path.lastIndexOf("/webdav");        	         	
	        	if (index > lastIndex) {
	        		apiName = "webdav";        		
	        		escapedCollectionName = path.substring(index + "/webdav".length());
	        		context = path.substring(0, index) + "/webdav";
	        		lastIndex = index;
	        	}    		
	        	//Default : a local URI...
	        	if (apiName == null) {	    			
	        		apiName = "rest-style";  
	        		escapedCollectionName =  path; 	
	    			//TODO : determine the context out of a clean root collection policy.
	    			context = null;	        		        		
	        	}
    		}    	
	        else 
	        {	        	
	        	if (port > -1)
	        		//Put the "right" URI in the message ;-)
	        		throw new URISyntaxException(wrappedURI.toString(), "Local xmldb URI should not provide a port");
	        	apiName = "direct access";  
	        	context = null;
	        	escapedCollectionName = path; 	 
	        }
	    	//Trim trailing slash if necessary    	
	    	if (escapedCollectionName != null && escapedCollectionName.length() > 1 && escapedCollectionName.endsWith("/"))    		
	    		escapedCollectionName = escapedCollectionName.substring(0, escapedCollectionName.length() - 1);              	
	    	//TODO : check that collectionName starts with DBBroker.ROOT_COLLECTION ?					
		}		
		
	}

	/** To be called each time a private member that interacts with the wrapped URI is modified.
	 * @throws URISyntaxException
	 */
	private void recomputeURI() throws URISyntaxException {		
		StringBuffer buf = new StringBuffer();
		if (instanceName != null)	
			buf.append(XMLDB_URI_PREFIX).append(instanceName).append("://");
		if (host != null)	
			buf.append(host);				
		if (port > -1)
			buf.append(":" + port);		
		if (context != null)
			buf.append(context);
		//TODO : eventually use a prepend.root.collection system property 		
		if (escapedCollectionName != null)
			buf.append(escapedCollectionName);
		try {
			wrappedURI = new URI(buf.toString());			
    	} catch (URISyntaxException e) {
        	wrappedURI = null;        	
        	throw e; 
    	}			
	}
	
	/** To be called before a context operation with another XmldbURI.
	 * @param uri
	 * @throws IllegalArgumentException
	 */
	private void checkCompatibilityForContextOperation(XmldbURI uri) throws IllegalArgumentException {
		if (this.getInstanceName() != null && uri.getInstanceName() != null
				&& !this.getInstanceName().equals(uri.getInstanceName()))
			throw new IllegalArgumentException(this.getInstanceName() + " instance differs from " + uri.getInstanceName());
		//case insensitive comparison
		if (this.getHost() != null && uri.getHost() != null
				&& !this.getHost().equalsIgnoreCase(uri.getHost()))
			throw new IllegalArgumentException(this.getHost() + " host differs from " + uri.getHost());
		if (this.getPort() != -1 && uri.getPort() != -1	&& this.getPort() != uri.getPort())
			throw new IllegalArgumentException(this.getPort() + " port differs from " + uri.getPort());
		if (this.getCollectionName() != null && uri.getCollectionName() != null
				&& !this.getCollectionName().equals(uri.getCollectionName()))
			throw new IllegalArgumentException(this.getCollectionName() + " collection differs from " + uri.getCollectionName());		
	}

	/** To be called before a collection name operation with another XmldbURI.
	 * @param uri
	 * @throws IllegalArgumentException
	 */
	private void checkCompatibilityForCollectionOperation(XmldbURI uri) throws IllegalArgumentException {
		if (this.getInstanceName() != null && uri.getInstanceName() != null
				&& !this.getInstanceName().equals(uri.getInstanceName()))
			throw new IllegalArgumentException(this.getInstanceName() + " instance differs from " + uri.getInstanceName());
		//case insensitive comparison
		if (this.getHost() != null && uri.getHost() != null
				&& !this.getHost().equalsIgnoreCase(uri.getHost()))
			throw new IllegalArgumentException(this.getHost() + " host differs from " + uri.getHost());
		if (this.getPort() != -1 && uri.getPort() != -1	&& this.getPort() != uri.getPort())
			throw new IllegalArgumentException(this.getPort() + " port differs from " + uri.getPort());
		if (this.getContext() != null && uri.getContext() != null
				&& !this.getContext().equals(uri.getContext()))
			throw new IllegalArgumentException(this.getContext() + " context differs from " + uri.getContext());		
	}
	
	public void setInstanceName(String instanceName) throws URISyntaxException {		 
		String oldInstanceName = this.instanceName;
		try {
			this.instanceName = instanceName;
			recomputeURI();
		} catch (URISyntaxException e) {
			this.instanceName = oldInstanceName;
			throw e;
		}			
	}
	
	public void setHost(String host) throws URISyntaxException {
		String oldHost = this.host;
		try {
			this.host = host;
			recomputeURI();
		} catch (URISyntaxException e) {
			this.host = oldHost;
			throw e;
		}
	}
	
	public void setPort(int port) throws URISyntaxException {
		//TODO : check range ?
		int oldPort = this.port;
		try {
			this.port = port;
			recomputeURI();
		} catch (URISyntaxException e) {
			this.port = oldPort;
			throw e;
		}
	}
	
	public void setContext(String context) throws URISyntaxException {
		String oldContext = this.context;
		try {
			//trims any trailing slash 
	    	if (context != null && context.endsWith("/")) {   		
	    		//include root slash if we have a host
	    		if (this.getHost() != null)
	    			context = context.substring(0, context.length() - 1); 
	    	}
			this.context = "".equals(context) ? null : context;
			recomputeURI();
		} catch (URISyntaxException e) {
			this.context = oldContext;
			throw e;
		}
	}
	
	public void setContext(URI context) throws URISyntaxException {
		String str = context.toString();
		setContext(str);		
	}	
	
	public void setCollectionName(String collectionName) throws URISyntaxException {
		String oldCollectionName = collectionName;
		try {
			if (collectionName == null)
				this.escapedCollectionName = null;
			else {
				String escaped = URLEncoder.encode(collectionName, "UTF-8");
				//This is the trick : unescape slashed in order to keep java.net.URI capabilities
				escaped = escaped.replaceAll("%2F", "/");
				escaped = escaped.replaceAll("%23", "#");
				escaped = escaped.replaceAll("%3F", "?");			
				this.escapedCollectionName = escaped;
			}
			recomputeURI();
		} catch (URISyntaxException e) {
			this.escapedCollectionName = oldCollectionName;
			throw e;
    	} catch (UnsupportedEncodingException e) {
        	wrappedURI = null;        	
        	throw new URISyntaxException(this.toString(), e.getMessage());  	
    	}
	}
	
	public void setCollectionName(URI collectionName) throws URISyntaxException {
		String str = context.toString();
		setCollectionName(str);			
	}
	
	public URI getURI() { 			
		return wrappedURI; 
	}
	
	public String getInstanceName() {		
		return instanceName; 
	}
	
	public String getHost() { 		
		return host; 
	}
	public int getPort() {		
		return port; 
	}
	public String getCollectionName() {
		if (escapedCollectionName == null)
			return null;
		try {
			return URLDecoder.decode(escapedCollectionName, "UTF-8"); 
		} catch (UnsupportedEncodingException e) {
			//Should never happen
			throw new IllegalArgumentException(escapedCollectionName + " can not be properly escaped");
		}		
	}
	
	public String getApiName() {		
		return apiName; 
	}
	
	public String getContext() {		
		return context; 
	}
	
	public int compareTo(Object ob) throws ClassCastException {
		if (!(ob instanceof XmldbURI))
			throw new ClassCastException("The provided Object is not an XmldbURI");		
		return wrappedURI.compareTo(((XmldbURI)ob).getURI());
	}
	
	public static XmldbURI create(String str) {		
		try {
			return new XmldbURI(str);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}
	
	public static XmldbURI create(String accessURI, String collectionName) {		
		try {
			return new XmldbURI(accessURI, collectionName);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}
	
	//Ugly workaround for non-URI compliant collection names
	public static String recoverPseudoURIs(String pseudoURI) {		
		Pattern p = Pattern.compile("/");
		String[] parts = p.split(pseudoURI);
		StringBuffer newURIString = new StringBuffer(parts[0]);
		for (int i = 1 ; i <parts.length; i ++) { 
			newURIString.append("/");
			if (!"".equals(parts[i])) {
	    		try {
	    			URI dummy = new URI(parts[i]); 
	    			newURIString.append(parts[i]);
	    		} catch (URISyntaxException e) {	
	    			//We'd nee a logger here.
	    			System.out.println("Had to escape : ''" + parts[i] + "' in '" + pseudoURI + "' !");    		
	    			try {
	    				newURIString.append(URLEncoder.encode(parts[i], "UTF-8"));
	    			} catch (UnsupportedEncodingException ee) {
	    				//We'd nee a logger here.
		    			System.out.println("Can't do anything with : ''" + parts[i] + "' in '" + pseudoURI + "' !");    	
	    				return null;
	    			}
	    		}
			}			
		}
		return newURIString.toString();
	}
	
	public boolean equals(Object ob) {
		if (!(ob instanceof XmldbURI))
			return false;
		return wrappedURI.equals(((XmldbURI)ob).getURI());
	}	
	
	public boolean isAbsolute() {	
		return wrappedURI.isAbsolute();
	}
	
	public boolean isContextAbsolute() {
		String context = this.getContext();
		if (context == null)
			return true;
		return context.startsWith("/");
	}
	
	public XmldbURI normalizeContext() {			
		String context = this.getContext();
		if (context == null)
			return this;
		URI uri = URI.create(context);		
		try {
			XmldbURI xmldbURI = new XmldbURI(this.toString());
			xmldbURI.setContext((uri.normalize()).toString());
			return xmldbURI;
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}	
	
	public URI relativizeContext(URI uri) {
		if (uri == null)
			throw new NullPointerException("The provided URI is null");			
		String context = this.getContext();
		if (context == null)
			throw new NullPointerException("The current context is null");		
		URI contextURI;
		//Adds a final slash if necessary
		if (!context.endsWith("/"))
			contextURI = URI.create(context + "/");
		else
			contextURI = URI.create(context);		
		return contextURI.relativize(uri);	
	}
	
	public URI resolveContext(String str) throws NullPointerException, IllegalArgumentException {	
		if (str == null)
			throw new NullPointerException("The provided URI is null");		
		String context = this.getContext();
		if (context == null)
			throw new NullPointerException("The current context is null");
		URI contextURI;
		//Adds a final slash if necessary
		if (!context.endsWith("/"))
			contextURI = URI.create(context + "/");
		else
			contextURI = URI.create(context);		
		return contextURI.resolve(str);	
	}
	
	public URI resolveContext(URI uri) throws NullPointerException {	
		if (uri == null)
			throw new NullPointerException("The provided URI is null");		
		String context = this.getContext();
		if (context == null)
			throw new NullPointerException("The current context is null");	
		URI contextURI;
		//Adds a final slash if necessary
		if (!context.endsWith("/"))
			contextURI = URI.create(context + "/");
		else
			contextURI = URI.create(context);		
		return contextURI.resolve(uri);	
	}
	
	public boolean isCollectionNameAbsolute() {
		String collectionName = this.escapedCollectionName;
		if (collectionName == null)
			return true;
		return collectionName.startsWith("/");
	}
	
	public XmldbURI normalizeCollectionName() {			
		String collectionName = this.escapedCollectionName;
		if (collectionName == null)
			return this;
		URI collectionNameURI = URI.create(collectionName);	
		try {
			XmldbURI xmldbURI = new XmldbURI(this.toString());
			xmldbURI.setCollectionName(collectionNameURI.normalize().toString());
			return xmldbURI;
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
	}	

	public URI relativizeCollectionName(URI uri) {
		if (uri == null)
			throw new NullPointerException("The provided URI is null");			
		String collectionName = this.escapedCollectionName;
		if (collectionName == null)
			throw new NullPointerException("The current collection name is null");		
		URI collectionNameURI;
		//Adds a final slash if necessary
		if (!collectionName.endsWith("/"))
			collectionNameURI = URI.create(collectionName + "/");
		else
			collectionNameURI = URI.create(collectionName);
		return collectionNameURI.relativize(uri);	
	}
	
	public URI resolveCollectionName(String str) throws NullPointerException, IllegalArgumentException {	
		if (str == null)
			throw new NullPointerException("The provided URI is null");		
		String collectionName = this.escapedCollectionName;
		if (collectionName == null)
			throw new NullPointerException("The current collection name is null");	
		URI collectionNameURI;
		//Adds a final slash if necessary
		if (!collectionName.endsWith("/"))
			collectionNameURI = URI.create(collectionName + "/");
		else
			collectionNameURI = URI.create(collectionName);
		return collectionNameURI.resolve(str);	
	}
	
	public URI resolveCollectionName(URI uri) throws NullPointerException {	
		if (uri == null)
			throw new NullPointerException("The provided URI is null");		
		String collectionName = this.escapedCollectionName;
		if (collectionName == null)
			throw new NullPointerException("The current collection name is null");
		URI collectionNameURI;
		//Adds a final slash if necessary
		if (!collectionName.endsWith("/"))
			collectionNameURI = URI.create(collectionName + "/");
		else
			collectionNameURI = URI.create(collectionName);
		return collectionNameURI.resolve(uri);	
	}	
	
	public String toASCIIString() {	
		//TODO : trim trailing slash if necessary
		return wrappedURI.toASCIIString();
	}
	
	public URL toURL() throws IllegalArgumentException, MalformedURLException {			
		return wrappedURI.toURL();
	}
	
	public String toString() {	
		//TODO : trim trailing slash if necessary
		return wrappedURI.toString();
	}
	
//	TODO : prefefined URIs as static classes...

}
