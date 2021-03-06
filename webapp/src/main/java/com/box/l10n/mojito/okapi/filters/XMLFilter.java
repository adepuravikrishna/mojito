package com.box.l10n.mojito.okapi.filters;

import com.box.l10n.mojito.service.assetExtraction.extractor.AssetPathToFilterConfigMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import net.sf.okapi.common.BOMNewlineEncodingDetector;
import net.sf.okapi.common.DefaultEntityResolver;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.encoder.EncoderManager;
import net.sf.okapi.common.exceptions.OkapiIOException;
import net.sf.okapi.common.filters.FilterConfiguration;
import net.sf.okapi.common.resource.Property;
import net.sf.okapi.common.resource.StartDocument;
import net.sf.okapi.common.resource.TextContainer;
import net.sf.okapi.common.resource.TextUnit;
import net.sf.okapi.common.skeleton.GenericSkeleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 *
 * @author jyi
 */
public class XMLFilter extends net.sf.okapi.filters.xml.XMLFilter {

    /**
     * logger
     */
    static Logger logger = LoggerFactory.getLogger(XMLFilter.class);

    public static final String FILTER_CONFIG_ID = "okf_xml@mojito";
    public static final String ANDROIDSTRINGS_CONFIG_FILE_NAME = "AndroidStrings_mojito.fprm";
    public static final String RESX_CONFIG_FILE_NAME = "resx_mojito.fprm";
    public static final String XTB_CONFIG_FILE_NAME = "xtb_mojito.fprm";
    
    private static final String XML_COMMENT_PATTERN = "<!--(?<comment>.*?)-->";
    private static final String XML_COMMENT_GROUP_NAME = "comment";
    

    @Override
    public String getName() {
        return FILTER_CONFIG_ID;
    }

    /**
     * Overriding to include only resx and AndroidStrings filters
     *
     * @return
     */
    @Override
    public List<FilterConfiguration> getConfigurations() {
        List<FilterConfiguration> list = new ArrayList<>();
        list.add(new FilterConfiguration(getName() + "-resx",
                getMimeType(),
                getClass().getName(),
                "RESX",
                "Configuration for Microsoft RESX documents (without binary data).",
                RESX_CONFIG_FILE_NAME,
                ".resx;"));
        list.add(new FilterConfiguration(getName() + "-AndroidStrings",
                getMimeType(),
                getClass().getName(),
                "Android Strings",
                "Configuration for Android Strings XML documents.",
                ANDROIDSTRINGS_CONFIG_FILE_NAME));
        list.add(new FilterConfiguration(getName() + "-xtb",
                getMimeType(),
                getClass().getName(),
                "XTB",
                "Configuration for XTB documents.",
                XTB_CONFIG_FILE_NAME));
        return list;
    }

    /**
     * Overriding to fix the parsing exception when the input encoding is UTF-16
     */
    @Override
    protected void initializeDocument() {
        // Create the document builder factory
        DocumentBuilderFactory fact = DocumentBuilderFactory.newInstance();
        fact.setNamespaceAware(true);
        fact.setValidating(false);

        // security concern. Turn off DTD processing
        // https://www.owasp.org/index.php/XML_External_Entity_%28XXE%29_Processing
        try {
            // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-general-entities
            // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-general-entities
            fact.setFeature("http://xml.org/sax/features/external-general-entities", false);
        } catch (ParserConfigurationException e) {
            // Tried an unsupported feature. This may indicate that a different XML processor is being
            // used. If so, then its features need to be researched and applied correctly.
            // For example, using the Xerces 2 feature above on a Xerces 1 processor will throw this
            // exception.
            logger.warn("Unsupported DocumentBuilderFactory feature. Possible security vulnerabilities.", e);
        }

        // Expand entity references only if we do not protect them
        // "Expand entity" means don't have ENTITY_REFERENCE
        fact.setExpandEntityReferences(!params.protectEntityRef);

        // Create the document builder
        DocumentBuilder docBuilder;
        try {
            docBuilder = fact.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new OkapiIOException(e);
        }
        //TODO: Do this only as an option
        // Avoid DTD declaration
        docBuilder.setEntityResolver(new DefaultEntityResolver());

        input.setEncoding("UTF-8"); // Default for XML, other should be auto-detected
        BOMNewlineEncodingDetector detector = new BOMNewlineEncodingDetector(input.getStream(), input.getEncoding());
        detector.detectAndRemoveBom();

        if (detector.isAutodetected()) {
            encoding = detector.getEncoding();
            //--Start workaround issue with XML Parser
            // "UTF-16xx" are not handled as expected, using "UTF-16" alone 
            // seems to resolve the issue.
            if ((encoding.equals("UTF-16LE")) || (encoding.equals("UTF-16BE"))) {
                encoding = "UTF-16";
            }
            //--End workaround
            input.setEncoding(encoding);
        }

        try {
            InputSource is = new InputSource(input.getStream());
            //InputSource is = new InputSource(new BOMInputStream(input.getStream(), false));

            //--Fix to handle UTF-16 encoding
            //If input encoding is UTF-16, we need to explictly set encoding on the InputSource
            is.setEncoding(encoding);

            doc = docBuilder.parse(is);
        } catch (SAXException e) {
            throw new OkapiIOException("Parsing error.\n" + e.getMessage(), e);
        } catch (IOException e) {
            throw new OkapiIOException("IO Error when reading the document.\n" + e.getMessage(), e);
        }

        encoding = doc.getXmlEncoding();
        if (encoding == null) {
            encoding = detector.getEncoding();
        }
        srcLang = input.getSourceLocale();
        if (srcLang == null) {
            throw new NullPointerException("Source language not set.");
        }
        hasUTF8BOM = detector.hasUtf8Bom();
        lineBreak = detector.getNewlineType().toString();
        if (input.getInputURI() != null) {
            docName = input.getInputURI().getPath();
        }
    }

    /**
     * Overriding to use xml encoding from the input xml document
     *
     * @param startDoc
     */
    @Override
    protected void createStartDocumentSkeleton(StartDocument startDoc) {
        // Add the XML declaration
        skel = new GenericSkeleton();
        if (!params.omitXMLDeclaration && doc.getXmlEncoding() != null) {
            skel.add("<?xml version=\"" + doc.getXmlVersion() + "\"");
            skel.add(" encoding=\"" + doc.getXmlEncoding() + "\"");
            if (doc.getXmlStandalone()) {
                skel.add(" standalone=\"yes\"");
            }
            skel.add("?>" + lineBreak);
        }
    }

    @Override
    public Event next() {
        Event event = super.next();

        if (input.getFilterConfigId().equals(AssetPathToFilterConfigMapper.ANDROIDSTRINGS_FILTER_CONFIG_ID)
                && event.getEventType() == EventType.TEXT_UNIT) {
            // if source has escaped double-quotes, single-quotes, \r or \n, unescape
            TextUnit textUnit = (TextUnit) event.getTextUnit();
            String sourceString = textUnit.getSource().toString();
            String unescapedSourceString = unescape(sourceString);
            TextContainer source = new TextContainer(unescapedSourceString);
            textUnit.setSource(source);
            extractNoteFromXMLCommentInSkeletonIfNone(textUnit);
        }

        return event;
    }

    /**
     * Extract the note from XML comments only if there is no note on the 
     * text unit. In other words if a note was specify via attribute like 
     * description for android it won't be overridden by an comments present in 
     * the XML file.
     * 
     * @param textUnit the text unit for which comments should be extracted
     */
    protected void extractNoteFromXMLCommentInSkeletonIfNone(TextUnit textUnit) {

        String skeleton = textUnit.getSkeleton().toString();

        if (textUnit.getProperty(Property.NOTE) == null) {
            String note = getNoteFromXMLCommentsInSkeleton(skeleton);
            if (note != null) {
                textUnit.setProperty(new Property(Property.NOTE, note));
            }
        }
    }

    /**
     * Gets the note from the XML comments in the skeleton.
     * 
     * @param skeleton that may contains comments
     * @return the note or <code>null</code>
     */
    protected String getNoteFromXMLCommentsInSkeleton(String skeleton) {

        String note = null;

        StringBuilder commentBuilder = new StringBuilder();

        Pattern pattern = Pattern.compile(XML_COMMENT_PATTERN);
        Matcher matcher = pattern.matcher(skeleton);

        while (matcher.find()) {
            if(commentBuilder.length() > 0) {
                commentBuilder.append(" ");
            }
            commentBuilder.append(matcher.group(XML_COMMENT_GROUP_NAME).trim());
        }

        if (commentBuilder.length() > 0) {
            note = commentBuilder.toString();
        }

        return note;
    }
    
    private String unescape(String text) {
        String unescapedText = text.replaceAll("(\\\\)(\"|')", "$2");
        unescapedText = unescapedText.replaceAll("\\\\n", "\n");
        unescapedText = unescapedText.replaceAll("\\\\r", "\r");
        return unescapedText;
    }

    @Override
    public EncoderManager getEncoderManager() {
        if (encoderManager == null) {
            encoderManager = new EncoderManager();
        }

        XMLEncoder xmlEncoder = new XMLEncoder();

        if (input.getFilterConfigId().equals(getName() + "-AndroidStrings")) {
            xmlEncoder.setAndroidStrings(true);
        }

        encoderManager.setMapping(getMimeType(), xmlEncoder);
        return encoderManager;
    }
}
