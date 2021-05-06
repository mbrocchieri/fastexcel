package org.dhatim.fastexcel.reader;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static org.dhatim.fastexcel.reader.DefaultXMLInputFactory.factory;

class OPCPackage implements AutoCloseable {
    private static final Pattern filenameRegex = Pattern.compile("^(.*/)([^/]+)$");
    private final ZipFile zip;
    private final Map<String, String> workbookPartsById;
    private final PartEntryNames parts;
    private final List<String> formatIdList;
    private Map<String, String> fmtIdToFmtString;

    private OPCPackage(File zipFile) throws IOException {
        this(zipFile, false);
    }

    private OPCPackage(File zipFile, boolean withFormat) throws IOException {
        this(new ZipFile(zipFile), withFormat);
    }

    private OPCPackage(SeekableInMemoryByteChannel channel, boolean withStyle) throws IOException {
        this(new ZipFile(channel), withStyle);
    }

    private OPCPackage(ZipFile zip, boolean withFormat) throws IOException {
        try {
            this.zip = zip;
            this.parts = extractPartEntriesFromContentTypes();
            if (withFormat) {
                this.formatIdList = extractFormat(parts.style);
            } else {
                this.formatIdList = Collections.emptyList();
            }
            this.workbookPartsById = readWorkbookPartsIds(relsNameFor(parts.workbook));
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
    }

    private static String relsNameFor(String entryName) {
        return filenameRegex.matcher(entryName).replaceFirst("$1_rels/$2.rels");
    }

    static OPCPackage open(File inputFile) throws IOException {
        return open(inputFile, false);
    }

    static OPCPackage open(File inputFile, boolean withFormat) throws IOException {
        return new OPCPackage(inputFile, withFormat);
    }

    static OPCPackage open(InputStream inputStream) throws IOException {
        return open(inputStream, false);
    }

    static OPCPackage open(InputStream inputStream, boolean withFormat) throws IOException {
        byte[] compressedBytes = toByteArray(inputStream);
        return new OPCPackage(new SeekableInMemoryByteChannel(compressedBytes), withFormat);
    }

    private static byte[] toByteArray(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    public static long copy(InputStream input, OutputStream output) throws IOException {
        return copy(input, output, 8024);
    }

    public static long copy(InputStream input, OutputStream output, int buffersize) throws IOException {
        if (buffersize < 1) {
            throw new IllegalArgumentException("buffersize must be bigger than 0");
        }
        final byte[] buffer = new byte[buffersize];
        int n;
        long count = 0;
        while (-1 != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    private Map<String, String> readWorkbookPartsIds(String workbookRelsEntryName) throws IOException, XMLStreamException {
        String xlFolder = workbookRelsEntryName.substring(0, workbookRelsEntryName.indexOf("_rel"));
        Map<String, String> partsIdById = new HashMap<>();
        SimpleXmlReader rels = new SimpleXmlReader(factory, getRequiredEntryContent(workbookRelsEntryName));
        while (rels.goTo("Relationship")) {
            String id = rels.getAttribute("Id");
            String target = rels.getAttribute("Target");
            // if name does not start with /, it is a relative path
            if (!target.startsWith("/")) {
                target = xlFolder + target;
            } // else it is an absolute path
            partsIdById.put(id, target);
        }
        return partsIdById;
    }

    private PartEntryNames extractPartEntriesFromContentTypes() throws XMLStreamException, IOException {
        PartEntryNames entries = new PartEntryNames();
        final String contentTypesXml = "[Content_Types].xml";
        try (SimpleXmlReader reader = new SimpleXmlReader(factory, getRequiredEntryContent(contentTypesXml))) {
            while (reader.goTo(() -> reader.isStartElement("Override"))) {
                String contentType = reader.getAttributeRequired("ContentType");
                if (PartEntryNames.WORKBOOK_MAIN_CONTENT_TYPE.equals(contentType)
                        || PartEntryNames.WORKBOOK_EXCEL_MACRO_ENABLED_MAIN_CONTENT_TYPE.equals(contentType)) {
                    entries.workbook = reader.getAttributeRequired("PartName");
                } else if (PartEntryNames.SHARED_STRINGS_CONTENT_TYPE.equals(contentType)) {
                    entries.sharedStrings = reader.getAttributeRequired("PartName");
                } else if (PartEntryNames.STYLE_CONTENT_TYPE.equals(contentType)) {
                    entries.style = reader.getAttributeRequired("PartName");
                }
                if (entries.isFullyFilled()) {
                    break;
                }
            }
            if (entries.workbook == null) {
                // in case of a default workbook path, we got this
                // <Default Extension="xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml" />
                entries.workbook = "/xl/workbook.xml";
            }
        }
        return entries;
    }

    private List<String> extractFormat(String styleXml) throws XMLStreamException, IOException {
        List<String> fmtIdList = new ArrayList<>();
        fmtIdToFmtString = new HashMap<>();
        try (SimpleXmlReader reader = new SimpleXmlReader(factory, getRequiredEntryContent(styleXml))) {
            AtomicBoolean insideCellXfs = new AtomicBoolean(false);
            while (reader.goTo(() -> reader.isStartElement("numFmt") ||
                    reader.isStartElement("cellXfs") || reader.isEndElement("cellXfs") ||
                    insideCellXfs.get())) {
                if (reader.isStartElement("cellXfs")) {
                    insideCellXfs.set(true);
                } else if (reader.isEndElement("cellXfs")) {
                    insideCellXfs.set(false);
                }
                if ("numFmt".equals(reader.getLocalName())) {
                    String formatCode = reader.getAttributeRequired("formatCode");
                    fmtIdToFmtString.put(reader.getAttributeRequired("numFmtId"), formatCode);
                } else if (insideCellXfs.get() && reader.isStartElement("xf")) {
                    fmtIdList.add(reader.getAttribute("numFmtId"));
                }
            }
        }
        return fmtIdList;
    }

    private InputStream getRequiredEntryContent(String name) throws IOException {
        return Optional.ofNullable(getEntryContent(name))
                .orElseThrow(() -> new ExcelReaderException(name + " not found"));
    }

    InputStream getSharedStrings() throws IOException {
        return getEntryContent(parts.sharedStrings);
    }

    private InputStream getEntryContent(String name) throws IOException {
        if (name == null) {
            return null;
        }
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        ZipArchiveEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        return zip.getInputStream(entry);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    public InputStream getWorkbookContent() throws IOException {
        return getRequiredEntryContent(parts.workbook);
    }

    public InputStream getSheetContent(Sheet sheet) throws IOException {
        String name = this.workbookPartsById.get(sheet.getId());
        if (name == null) {
            String msg = format("Sheet#%s '%s' is missing an entry in workbook rels (for id: '%s')",
                    sheet.getIndex(), sheet.getName(), sheet.getId());
            throw new ExcelReaderException(msg);
        }
        return getRequiredEntryContent(name);
    }

    public List<String> getFormatList() {
        return formatIdList;
    }

    public Map<String, String> getFmtIdToFmtString() {
        return fmtIdToFmtString;
    }

    private static class PartEntryNames {
        public static final String WORKBOOK_MAIN_CONTENT_TYPE =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
        public static final String WORKBOOK_EXCEL_MACRO_ENABLED_MAIN_CONTENT_TYPE =
                "application/vnd.ms-excel.sheet.macroEnabled.main+xml";
        public static final String SHARED_STRINGS_CONTENT_TYPE =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml";
        public static final String STYLE_CONTENT_TYPE =
                "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml";
        String workbook;
        String sharedStrings;
        String style;

        boolean isFullyFilled() {
            return workbook != null && sharedStrings != null && style != null;
        }
    }
}
