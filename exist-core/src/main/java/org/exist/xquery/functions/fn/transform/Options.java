/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.exist.xquery.functions.fn.transform;

import com.evolvedbinary.j8fu.tuple.Tuple2;
import io.lacuna.bifurcan.IEntry;
import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import net.sf.saxon.expr.parser.RetainedStaticContext;
import net.sf.saxon.functions.SystemProperty;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmValue;
import org.apache.commons.lang3.StringUtils;
import org.exist.dom.memtree.NamespaceNode;
import org.exist.security.PermissionDeniedException;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.array.ArrayType;
import org.exist.xquery.functions.fn.FnTransform;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.util.DocUtils;
import org.exist.xquery.value.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.annotation.Nullable;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static com.evolvedbinary.j8fu.tuple.Tuple.Tuple;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.exist.Namespaces.XSL_NS;
import static org.exist.xquery.functions.fn.transform.Options.Option.*;
import static org.exist.xquery.functions.fn.transform.Transform.SAXON_CONFIGURATION;
import static org.exist.xquery.functions.fn.transform.Transform.toSaxon;

/**
 * Read options into class values in a single place.
 * <p></p>
 * This is a bit clearer where we need an option several times,
 * we know we have read it up front.
 */
class Options {

    static final javax.xml.namespace.QName QN_XSL_STYLESHEET = new javax.xml.namespace.QName(XSL_NS, "stylesheet");
    static final javax.xml.namespace.QName QN_VERSION = new javax.xml.namespace.QName("version");

    private static final long XXHASH64_SEED = 0x2245a28e;
    private static final XXHash64 XX_HASH_64 = XXHashFactory.fastestInstance().hash64();

    private Map<QName, XdmValue> readParamsMap(final Optional<MapType> option, final String name) throws XPathException {

        final Map<net.sf.saxon.s9api.QName, XdmValue> result = new HashMap<>();

        final MapType paramsMap = option.orElse(new MapType(context));
        for (final IEntry<AtomicValue, Sequence> entry : paramsMap) {
            final AtomicValue key = entry.key();
            if (!(key instanceof QNameValue)) {
                throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Supplied " + name + " is not a valid xs:qname: " + entry);
            }
            if (entry.value() == null) {
                throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Supplied " + name + " is not a valid xs:sequence: " + entry);
            }
            result.put(Convert.ToSaxon.of((QNameValue) key), toSaxon.of(entry.value()));
        }
        return result;
    }

    final Tuple2<String, Source> xsltSource;
    final MapType stylesheetParams;
    final Map<net.sf.saxon.s9api.QName, XdmValue> staticParams;
    final float xsltVersion;
    final Optional<AnyURIValue> resolvedStylesheetBaseURI;
    final Optional<QNameValue> initialFunction;
    final Optional<ArrayType> functionParams;
    final Map<net.sf.saxon.s9api.QName, XdmValue> templateParams;
    final Map<net.sf.saxon.s9api.QName, XdmValue> tunnelParams;
    final Optional<QNameValue> initialTemplate;
    final Optional<QNameValue> initialMode;
    final Optional<NodeValue> sourceNode;
    final Optional<Item> globalContextItem;
    final Optional<Sequence> initialMatchSelection;
    final Optional<BooleanValue> shouldCache;
    final Delivery.Format deliveryFormat;
    final Optional<StringValue> baseOutputURI;
    final Optional<MapType> serializationParams;

    final Optional<Long> sourceTextChecksum;
    final String stylesheetNodeDocumentPath;

    final Optional<FunctionReference> postProcess;

    private final XQueryContext context;
    private final FnTransform fnTransform;

    Options(final XQueryContext context, final FnTransform fnTransform, final MapType options) throws XPathException {
        this.context = context;
        this.fnTransform = fnTransform;

        xsltSource = getStylesheet(options);

        stylesheetParams = Options.STYLESHEET_PARAMS.get(options).orElse(new MapType(context));
        for (final IEntry<AtomicValue, Sequence> entry : stylesheetParams) {
            if (!(entry.key() instanceof QNameValue)) {
                throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Supplied stylesheet-param is not a valid xs:qname: " + entry);
            }
            if (entry.value() == null) {
                throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Supplied stylesheet-param is not a valid xs:sequence: " + entry);
            }
        }

        final Optional<DecimalValue> explicitXsltVersion = Options.XSLT_VERSION.get(options);
        if (explicitXsltVersion.isPresent()) {
            try {
                xsltVersion = explicitXsltVersion.get().getFloat();
            } catch (final XPathException e) {
                throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Supplied xslt-version is not a valid xs:decimal: " + e.getMessage(), explicitXsltVersion.get(), e);
            }
        } else {
            xsltVersion = getXsltVersion(xsltSource._2);
        }

        final String stylesheetBaseUri;
        final Optional<StringValue> explicitStylesheetBaseUri = Options.STYLESHEET_BASE_URI.get(xsltVersion, options);
        if (explicitStylesheetBaseUri.isPresent()) {
            stylesheetBaseUri = explicitStylesheetBaseUri.get().getStringValue();
        } else {
            stylesheetBaseUri = xsltSource._1;
        }
        if (!StringUtils.isEmpty(stylesheetBaseUri)) {
            resolvedStylesheetBaseURI = Optional.of(resolveURI(new AnyURIValue(stylesheetBaseUri), context.getBaseURI()));
        } else {
            resolvedStylesheetBaseURI = Optional.empty();
        }
        initialFunction = Options.INITIAL_FUNCTION.get(options);
        functionParams = Options.FUNCTION_PARAMS.get(options);

        initialTemplate = Options.INITIAL_TEMPLATE.get(options);
        initialMode = Options.INITIAL_MODE.get(options);

        templateParams = readParamsMap(Options.TEMPLATE_PARAMS.get(options), Options.TEMPLATE_PARAMS.name.getStringValue());
        tunnelParams = readParamsMap(Options.TUNNEL_PARAMS.get(options), Options.TUNNEL_PARAMS.name.getStringValue());
        staticParams = readParamsMap(Options.STATIC_PARAMS.get(options), Options.STATIC_PARAMS.name.getStringValue());

        sourceNode = Options.SOURCE_NODE.get(options);
        globalContextItem = Options.GLOBAL_CONTEXT_ITEM.get(options);
        initialMatchSelection = Options.INITIAL_MATCH_SELECTION.get(options);
        if (sourceNode.isPresent() && initialMatchSelection.isPresent()) {
            throw new XPathException(ErrorCodes.FOXT0002,
                    "Both " + SOURCE_NODE.name + " and " + INITIAL_MATCH_SELECTION.name + " were supplied. " +
                            "These options cannot both be supplied.");
        }
        sourceTextChecksum = getSourceTextChecksum(options);
        stylesheetNodeDocumentPath = getStylesheetNodeDocumentPath(options);

        shouldCache = Options.CACHE.get(xsltVersion, options);

        deliveryFormat = getDeliveryFormat(xsltVersion, options);

        baseOutputURI = Options.BASE_OUTPUT_URI.get(xsltVersion, options);

        serializationParams = Options.SERIALIZATION_PARAMS.get(xsltVersion, options);

        validateRequestedProperties(Options.REQUESTED_PROPERTIES.get(xsltVersion, options).orElse(new MapType(context)));

        postProcess = Options.POST_PROCESS.get(xsltVersion, options);
    }

    private Delivery.Format getDeliveryFormat(final float xsltVersion, final MapType options) throws XPathException {
        final String string = Options.DELIVERY_FORMAT.get(xsltVersion, options).get().getStringValue().toUpperCase();
        final Delivery.Format deliveryFormat;
        try {
            deliveryFormat = Delivery.Format.valueOf(string);
        } catch (final IllegalArgumentException ie) {
            throw new XPathException(fnTransform, ErrorCodes.FOXT0002,
                    ": \"" + string + "\" is not a valid " + Options.DELIVERY_FORMAT.name);
        }
            /* TODO (AP) it's unclear (spec vs XQTS) if this is meant to happen, or not ??
            if (deliveryFormat == DeliveryFormat.RAW && xsltVersion < 3.0f) {
                throw new XPathException(FnTransform.this, ErrorCodes.FOXT0002, "Supplied " + FnTransform.DELIVERY_FORMAT.name +
                        " is not valid when using XSLT version " + xsltVersion);
            }
             */
        return deliveryFormat;
    }

    private Optional<Long> getSourceTextChecksum(final MapType options) throws XPathException {
        final Optional<String> stylesheetText = Options.STYLESHEET_TEXT.get(options).map(StringValue::getStringValue);
        if (stylesheetText.isPresent()) {
            final String text = stylesheetText.get();
            final byte[] data = text.getBytes(UTF_8);
            return Optional.of(Options.XX_HASH_64.hash(data, 0, data.length, Options.XXHASH64_SEED));
        }
        return Optional.empty();
    }

    private String getStylesheetNodeDocumentPath(final MapType options) throws XPathException {
        final Optional<Node> stylesheetNode = Options.STYLESHEET_NODE.get(options).map(NodeValue::getNode);
        return stylesheetNode.map(node -> TreeUtils.pathTo(node).toString()).orElse("");
    }

    private void validateRequestedProperties(final MapType requestedProperties) throws XPathException {
        for (final IEntry<AtomicValue, Sequence> entry : requestedProperties) {
            final AtomicValue key = entry.key();
            if (!Type.subTypeOf(key.getType(), Type.QNAME)) {
                throw new XPathException(ErrorCodes.XPTY0004, "Type error: requested-properties key: " + key + " is not a QName");
            }
            final Sequence value = entry.value();
            if (!value.hasOne()) {
                throw new XPathException(ErrorCodes.XPTY0004, "Type error: requested-properties " + key + " does not have a single item value.");
            }
            final Item item = value.itemAt(0);
            final String requiredPropertyValue;
            if (Type.subTypeOf(item.getType(), Type.STRING)) {
                requiredPropertyValue = item.getStringValue();
            } else if (Type.subTypeOf(item.getType(), Type.BOOLEAN)) {
                requiredPropertyValue = ((BooleanValue) item).getValue() ? "yes" : "no";
            } else {
                throw new XPathException(ErrorCodes.XPTY0004,
                        "Type error: requested-properties " + key +
                                " is not a " + Type.getTypeName(Type.STRING) +
                                " or a " + Type.getTypeName(Type.BOOLEAN));
            }
            final String actualPropertyValue = SystemProperties.get(((QNameValue) key).getQName());
            if (!actualPropertyValue.equalsIgnoreCase(requiredPropertyValue)) {
                throw new XPathException(ErrorCodes.FOXT0001,
                        "The XSLT processor cannot provide the requested-property: " + key +
                                " requested: " + requiredPropertyValue +
                                ", actual: " + actualPropertyValue);
            }
        }
    }
    private static final Option<StringValue> BASE_OUTPUT_URI = new ItemOption<>(
            Type.STRING, "base-output-uri", v1_0, v2_0, v3_0);
    private static final Option<BooleanValue> CACHE = new ItemOption<>(
            Type.BOOLEAN, "cache", BooleanValue.TRUE, v1_0, v2_0, v3_0);
    private static final Option<StringValue> DELIVERY_FORMAT = new ItemOption<>(
            Type.STRING, "delivery-format", new StringValue("document"), v1_0, v2_0, v3_0);
    private static final Option<BooleanValue> ENABLE_ASSERTIONS = new ItemOption<>(
            Type.BOOLEAN, "enable-assertions", BooleanValue.FALSE, v3_0);
    private static final Option<BooleanValue> ENABLE_MESSAGES = new ItemOption<>(
            Type.BOOLEAN, "enable-messages", BooleanValue.TRUE, v1_0, v2_0, v3_0);
    private static final Option<BooleanValue> ENABLE_TRACE = new ItemOption<>(
            Type.BOOLEAN, "enable-trace", BooleanValue.TRUE, v2_0, v3_0);
    private static final Option<ArrayType> FUNCTION_PARAMS = new ItemOption<>(
            Type.ARRAY,"function-params", v3_0);
    private static final Option<Item> GLOBAL_CONTEXT_ITEM = new ItemOption<>(
            Type.ITEM, "global-context-item", v3_0);
    static final Option<QNameValue> INITIAL_FUNCTION = new ItemOption<>(
            Type.QNAME,"initial-function", v3_0);
    static final Option<Sequence> INITIAL_MATCH_SELECTION = new SequenceOption<>(
            Type.ATOMIC,Type.NODE, "initial-match-selection", v3_0);
    static final Option<QNameValue> INITIAL_MODE = new ItemOption<>(
            Type.QNAME,"initial-mode", v1_0, v2_0, v3_0);
    static final Option<QNameValue> INITIAL_TEMPLATE = new ItemOption<>(
            Type.QNAME,"initial-template", v2_0, v3_0);
    private static final Option<StringValue> PACKAGE_NAME = new ItemOption<>(
            Type.STRING,"package-name", v3_0);
    private static final Option<StringValue> PACKAGE_LOCATION = new ItemOption<>(
            Type.STRING,"package-location", v3_0);
    private static final Option<NodeValue> PACKAGE_NODE = new ItemOption<>(
            Type.NODE,"package-node", v3_0);
    private static final Option<StringValue> PACKAGE_TEXT = new ItemOption<>(
            Type.STRING,"package-text", v3_0);
    private static final Option<StringValue> PACKAGE_VERSION = new ItemOption<>(
            Type.STRING,"package-version", new StringValue("*"), v3_0);
    private static final Option<FunctionReference> POST_PROCESS = new ItemOption<>(
            Type.FUNCTION_REFERENCE,"post-process", v1_0, v2_0, v3_0);
    private static final Option<MapType> REQUESTED_PROPERTIES = new ItemOption<>(
            Type.MAP,"requested-properties", v1_0, v2_0, v3_0);
    private static final Option<MapType> SERIALIZATION_PARAMS = new ItemOption<>(
            Type.MAP,"serialization-params", v1_0, v2_0, v3_0);
    static final Option<NodeValue> SOURCE_NODE = new ItemOption<>(
            Type.NODE,"source-node", v1_0, v2_0, v3_0);
    private static final Option<MapType> STATIC_PARAMS = new ItemOption<>(
            Type.MAP,"static-params", v3_0);
    private static final Option<StringValue> STYLESHEET_BASE_URI = new ItemOption<>(
            Type.STRING, "stylesheet-base-uri", v1_0, v2_0, v3_0);
    static final Option<StringValue> STYLESHEET_LOCATION = new ItemOption<>(
            Type.STRING,"stylesheet-location", v1_0, v2_0, v3_0);
    static final Option<NodeValue> STYLESHEET_NODE = new ItemOption<>(
            Type.NODE,"stylesheet-node", v1_0, v2_0, v3_0);
    private static final Option<MapType> STYLESHEET_PARAMS = new ItemOption<>(
            Type.MAP,"stylesheet-params", v1_0, v2_0, v3_0);
    static final Option<StringValue> STYLESHEET_TEXT = new ItemOption<>(
            Type.STRING,"stylesheet-text", v1_0, v2_0, v3_0);
    private static final Option<MapType> TEMPLATE_PARAMS = new ItemOption<>(
            Type.MAP,"template-params", v3_0);
    private static final Option<MapType> TUNNEL_PARAMS = new ItemOption<>(
            Type.MAP,"tunnel-params", v3_0);
    private static final Option<MapType> VENDOR_OPTIONS = new ItemOption<>(
            Type.MAP,"vendor-options", v1_0, v2_0, v3_0);
    private static final Option<DecimalValue> XSLT_VERSION = new ItemOption<>(
            Type.DECIMAL,"xslt-version", v1_0, v2_0, v3_0);

    static abstract class Option<T> {
        public static final float v1_0 = 1.0f;
        public static final float v2_0 = 2.0f;
        public static final float v3_0 = 3.0f;

        protected final StringValue name;
        protected final Optional<T> defaultValue;
        protected final float[] appliesToVersions;
        protected final int itemSubtype;

        private Option(final int itemSubtype, final String name, final Optional<T> defaultValue, final float... appliesToVersions) {
            this.name = new StringValue(name);
            this.defaultValue = defaultValue;
            this.appliesToVersions = appliesToVersions;
            this.itemSubtype = itemSubtype;
        }

        public abstract Optional<T> get(final MapType options) throws XPathException;

        private boolean notApplicableToVersion(final float xsltVersion) {
            for (final float appliesToVersion : appliesToVersions) {
                if (xsltVersion == appliesToVersion) {
                    return false;
                }
            }
            return true;
        }

        public Optional<T> get(final float xsltVersion, final MapType options) throws XPathException {
            if (notApplicableToVersion(xsltVersion)) {
                return Optional.empty();
            }

            return get(options);
        }
    }

    static class SequenceOption<T extends Sequence> extends Option<T> {

        private final int sequenceSubtype;

        public SequenceOption(final int sequenceSubtype, final int itemSubtype, final String name, final float... appliesToVersions) {
            super(itemSubtype, name, Optional.empty(), appliesToVersions);
            this.sequenceSubtype = sequenceSubtype;
        }

        public SequenceOption(final int sequenceSubtype, final int itemSubtype, final String name, @Nullable final T defaultValue, final float... appliesToVersions) {
            super(itemSubtype, name, Optional.ofNullable(defaultValue), appliesToVersions);
            this.sequenceSubtype = sequenceSubtype;
        }

        @Override
        public Optional<T> get(final MapType options) throws XPathException {
            if (options.contains(name)) {
                final Sequence sequence = options.get(name);
                if (sequence != null) {
                    if (Type.subTypeOf(sequence.getItemType(), sequenceSubtype)) {
                        return Optional.of((T) sequence);
                    }
                    final Item item0 = options.get(name).itemAt(0);
                    if (item0 != null) {
                        if (Type.subTypeOf(item0.getType(), itemSubtype)) {
                            return Optional.of((T) item0);
                        } else {
                            throw new XPathException(
                                    ErrorCodes.XPTY0004, "Type error: expected " + Type.getTypeName(itemSubtype) + ", got " + Type.getTypeName(sequence.getItemType()));
                        }
                    }
                }
            }
            return defaultValue;
        }
    }

    static class ItemOption<T extends Item> extends Option<T> {

        public ItemOption(final int itemSubtype, final String name, final float... appliesToVersions) {
            super(itemSubtype, name, Optional.empty(), appliesToVersions);
        }

        public ItemOption(final int itemSubtype, final String name, @Nullable final T defaultValue, final float... appliesToVersions) {
            super(itemSubtype, name, Optional.ofNullable(defaultValue), appliesToVersions);
        }

        @Override
        public Optional<T> get(final MapType options) throws XPathException {
            if (options.contains(name)) {
                final Item item0 = options.get(name).itemAt(0);
                if (item0 != null) {
                    if (Type.subTypeOf(item0.getType(), itemSubtype)) {
                        return Optional.of((T) item0);
                    } else {
                        throw new XPathException(
                                ErrorCodes.XPTY0004, "Type error: expected " + Type.getTypeName(itemSubtype) + ", got " + Type.getTypeName(item0.getType()));
                    }
                }
            }
            return defaultValue;
        }
    }

    /**
     * Get the stylesheet.
     *
     * @return a Tuple whose first value is the stylesheet base uri, and whose second
     *     value is the source for accessing the stylesheet
     */
    private Tuple2<String, Source> getStylesheet(final MapType options) throws XPathException {

        final List<Tuple2<String, Source>> results = new ArrayList<>(1);
        final Optional<String> stylesheetLocation = Options.STYLESHEET_LOCATION.get(options).map(StringValue::getStringValue);
        if (stylesheetLocation.isPresent()) {
            results.add(Tuple(stylesheetLocation.get(), resolveStylesheetLocation(stylesheetLocation.get())));
        }

        final Optional<Node> stylesheetNode = Options.STYLESHEET_NODE.get(options).map(NodeValue::getNode);
        if (stylesheetNode.isPresent()) {
            final Node node = stylesheetNode.get();
            results.add(Tuple(node.getBaseURI(), new DOMSource(node)));
        }

        final Optional<String> stylesheetText = Options.STYLESHEET_TEXT.get(options).map(StringValue::getStringValue);
        stylesheetText.ifPresent(s -> results.add(Tuple("", new StringSource(s))));

        if (results.size() > 1) {
            throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "More than one of stylesheet-location, stylesheet-node, and stylesheet-text was set");
        }

        if (results.isEmpty()) {
            throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "None of stylesheet-location, stylesheet-node, or stylesheet-text was set");
        }

        return results.get(0);
    }

    /**
     * Resolve the stylesheet location.
     * <p>
     *     It may be a dynamically configured document.
     *     Or a document within the database.
     * </p>
     * @param stylesheetLocation path or URI of stylesheet
     * @return a source wrapping the contents of the stylesheet
     * @throws XPathException if there is a problem resolving the location.
     */
    private Source resolveStylesheetLocation(final String stylesheetLocation) throws XPathException {

        final URI uri = URI.create(stylesheetLocation);
        if (uri.isAbsolute()) {
            return resolvePossibleStylesheetLocation(stylesheetLocation);
        } else {
            final AnyURIValue resolved = resolveURI(new AnyURIValue(stylesheetLocation), context.getBaseURI());
            return resolvePossibleStylesheetLocation(resolved.getStringValue());
        }
    }

    /**
     * Resolve an absolute stylesheet location
     *
     * @param location of the stylesheet
     * @return the resolved stylesheet as a source
     * @throws XPathException if the item does not exist, or is not a document
     */
    private Source resolvePossibleStylesheetLocation(final String location) throws XPathException {

        Sequence document;
        try {
            document = DocUtils.getDocument(context, location);
        } catch (final PermissionDeniedException e) {
            throw new XPathException(fnTransform, ErrorCodes.FODC0002,
                    "Can not access '" + location + "'" + e.getMessage());
        }
        if (document != null && document.hasOne() && Type.subTypeOf(document.getItemType(), Type.NODE)) {
            return new DOMSource((Node) document.itemAt(0));
        }
        throw new XPathException(fnTransform, ErrorCodes.FODC0002,
                "Location '"+ location + "' returns an item which is not a document node");
    }

    /**
     * URI resolution, the core should be the same as for fn:resolve-uri
     * @param relative URI to resolve
     * @param base to resolve against
     * @return resolved URI
     * @throws XPathException if resolution is not possible
     */
    private AnyURIValue resolveURI(final AnyURIValue relative, final AnyURIValue base) throws XPathException {
        final URI relativeURI;
        final URI baseURI;
        try {
            relativeURI = new URI(relative.getStringValue());
            baseURI = new URI(base.getStringValue() );
        } catch (final URISyntaxException e) {
            throw new XPathException(fnTransform, ErrorCodes.FORG0009, "unable to resolve a relative URI against a base URI in fn:transform(): " + e.getMessage(), null, e);
        }
        if (relativeURI.isAbsolute()) {
            return relative;
        } else {
            return new AnyURIValue(baseURI.resolve(relativeURI));
        }
    }

    private float getXsltVersion(final Source xsltStylesheet) throws XPathException {

        if (xsltStylesheet instanceof DOMSource) {
            return domExtractXsltVersion(xsltStylesheet);
        } else if (xsltStylesheet instanceof StreamSource) {
            return staxExtractXsltVersion(xsltStylesheet);
        }

        throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Unable to extract version from XSLT, unrecognised source");
    }

    private float domExtractXsltVersion(final Source xsltStylesheet) throws XPathException {

        Node node = ((DOMSource) xsltStylesheet).getNode();
        if (node instanceof Document) {
            node = ((Document) node).getDocumentElement();
        }

        String version = "";

        if (node instanceof Element) {

            final Element elem = (Element) node;
            if (XSL_NS.equals(node.getNamespaceURI())
                    && "stylesheet".equals(node.getLocalName())) {
                version = elem.getAttribute("version");
            }

            // No luck ? Search the attributes of a "simplified stylesheet"
            final NamedNodeMap attributes = elem.getAttributes();
            for (int i = 0; version.isEmpty() && i < attributes.getLength(); i++) {
                if (attributes.item(i) instanceof NamespaceNode) {
                    final NamespaceNode nsNode = (NamespaceNode) attributes.item(i);
                    final String uri = nsNode.getNodeValue();
                    final String localName = nsNode.getLocalName(); // "xsl"
                    if (XSL_NS.equals(uri)) {
                        version = elem.getAttribute(localName + ":version");
                    }
                }
            }
        }

        if (version.isEmpty()) {
            throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Unable to extract version from XSLT via DOM");
        }

        try {
            return Float.parseFloat(version);
        } catch (final NumberFormatException nfe) {
            throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Unable to extract version from XSLT via DOM. Value: " + version + " : " + nfe.getMessage());
        }
    }

    private float staxExtractXsltVersion(final Source xsltStylesheet) throws XPathException {
        try {
            final XMLInputFactory factory = XMLInputFactory.newInstance();
            final XMLEventReader eventReader =
                    factory.createXMLEventReader(xsltStylesheet);

            while (eventReader.hasNext()) {
                final XMLEvent event = eventReader.nextEvent();
                if (event.getEventType() == XMLStreamConstants.START_ELEMENT) {
                    final StartElement startElement = event.asStartElement();
                    if (Options.QN_XSL_STYLESHEET.equals(startElement.getName())) {
                        final Attribute version = startElement.getAttributeByName(Options.QN_VERSION);
                        return Float.parseFloat(version.getValue());
                    }
                }
            }
        } catch (final XMLStreamException e) {
            throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Unable to extract version from XSLT via STaX: " + e.getMessage(), Sequence.EMPTY_SEQUENCE, e);
        }

        throw new XPathException(fnTransform, ErrorCodes.FOXT0002, "Unable to extract version from XSLT via STaX");
    }

    private static class StringSource extends StreamSource {
        private final String string;

        public StringSource(final String string) {
            this.string = string;
        }

        @Override
        public Reader getReader() {
            return new StringReader(string);
        }
    }

    static class SystemProperties {
        private static final RetainedStaticContext retainedStaticContext = new RetainedStaticContext(SAXON_CONFIGURATION);

        static String get(org.exist.dom.QName qName) {
            return SystemProperty.getProperty(qName.getNamespaceURI(), qName.getLocalPart(), retainedStaticContext);
        }
    }
}

