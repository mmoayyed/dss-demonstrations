package eu.europa.esig.dss.asic.signature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import eu.europa.esig.dss.AbstractSignatureParameters;
import eu.europa.esig.dss.DSSDocument;
import eu.europa.esig.dss.DSSException;
import eu.europa.esig.dss.DSSUnsupportedOperationException;
import eu.europa.esig.dss.InMemoryDocument;
import eu.europa.esig.dss.SignatureValue;
import eu.europa.esig.dss.ToBeSigned;
import eu.europa.esig.dss.asic.ASiCExtractResult;
import eu.europa.esig.dss.asic.ASiCParameters;
import eu.europa.esig.dss.asic.ASiCUtils;
import eu.europa.esig.dss.asic.AbstractASiCContainerExtractor;
import eu.europa.esig.dss.signature.AbstractSignatureService;
import eu.europa.esig.dss.signature.MultipleDocumentsSignatureService;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.validation.CertificateVerifier;

public abstract class AbstractASiCSignatureService<SP extends AbstractSignatureParameters> extends AbstractSignatureService<SP>
		implements MultipleDocumentsSignatureService<SP> {

	private static final long serialVersionUID = 243114076381526665L;

	private static final String ZIP_ENTRY_DETACHED_FILE = "detached-file";
	private static final String ZIP_ENTRY_MIMETYPE = "mimetype";

	private ASiCExtractResult archiveContent = new ASiCExtractResult();

	protected AbstractASiCSignatureService(CertificateVerifier certificateVerifier) {
		super(certificateVerifier);
	}

	protected void assertCanBeSign(List<DSSDocument> documents, final ASiCParameters asicParameters) {
		if (!canBeSigned(documents, asicParameters)) { // First verify if the file can be signed
			throw new DSSUnsupportedOperationException("You only can sign an ASiC container by using the same type of container and of signature");
		}
	}

	private boolean canBeSigned(List<DSSDocument> documents, ASiCParameters asicParameters) {
		boolean isMimetypeCorrect = true;
		boolean isSignatureTypeCorrect = true;
		if (ASiCUtils.isArchive(documents)) {
			DSSDocument archive = documents.get(0);
			String expectedMimeType = archive.getMimeType().getMimeTypeString();
			String mimeTypeFromParameter = ASiCUtils.getMimeTypeString(asicParameters);
			isMimetypeCorrect = Utils.areStringsEqualIgnoreCase(expectedMimeType, mimeTypeFromParameter);
			if (isMimetypeCorrect) {
				isSignatureTypeCorrect = ASiCUtils.isArchiveContainsCorrectSignatureExtension(archive, getExpectedSignatureExtension());
			}
		}
		return isMimetypeCorrect && isSignatureTypeCorrect;
	}

	abstract String getExpectedSignatureExtension();

	@Override
	public ToBeSigned getDataToSign(DSSDocument toSignDocument, SP parameters) throws DSSException {
		return getDataToSign(Arrays.asList(toSignDocument), parameters);
	}

	@Override
	public DSSDocument signDocument(DSSDocument toSignDocument, SP parameters, SignatureValue signatureValue) throws DSSException {
		return signDocument(Arrays.asList(toSignDocument), parameters, signatureValue);
	}

	protected void extractCurrentArchive(DSSDocument archive) {
		AbstractASiCContainerExtractor extractor = getArchiveExtractor(archive);
		archiveContent = extractor.extract();
	}

	abstract AbstractASiCContainerExtractor getArchiveExtractor(DSSDocument archive);

	protected List<DSSDocument> getEmbeddedSignatures() {
		return archiveContent.getSignatureDocuments();
	}

	protected List<DSSDocument> getEmbeddedManifests() {
		return archiveContent.getManifestDocuments();
	}

	protected List<DSSDocument> getEmbeddedArchiveManifests() {
		return archiveContent.getArchiveManifestDocuments();
	}

	protected List<DSSDocument> getEmbeddedTimestamps() {
		return archiveContent.getTimestampDocuments();
	}

	protected List<DSSDocument> getEmbeddedSignedDocuments() {
		return archiveContent.getSignedDocuments();
	}

	protected DSSDocument getEmbeddedMimetype() {
		return archiveContent.getMimeTypeDocument();
	}

	protected DSSDocument mergeArchiveAndExtendedSignatures(DSSDocument archiveDocument, List<DSSDocument> signaturesToAdd) {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zos = new ZipOutputStream(baos)) {
			copyArchiveContentWithoutSignatures(archiveDocument, zos);
			storeDocuments(signaturesToAdd, zos);

			zos.finish();

			return new InMemoryDocument(baos.toByteArray(), null, archiveDocument.getMimeType());
		} catch (IOException e) {
			throw new DSSException("Unable to extend the ASiC container", e);
		}
	}

	private void copyArchiveContentWithoutSignatures(DSSDocument archiveDocument, ZipOutputStream zos) throws IOException {
		try (InputStream is = archiveDocument.openStream(); ZipInputStream zis = new ZipInputStream(is)) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				final String name = entry.getName();
				final ZipEntry newEntry = new ZipEntry(name);
				if (!isSignatureFilename(name)) {
					zos.putNextEntry(newEntry);
					Utils.copy(zis, zos);
				}
			}
		}
	}

	abstract boolean isSignatureFilename(String name);

	protected DSSDocument buildASiCContainer(List<DSSDocument> documentsToBeSigned, List<DSSDocument> signatures, List<DSSDocument> manifestDocuments,
			ASiCParameters asicParameters) {

		try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ZipOutputStream zos = new ZipOutputStream(baos)) {
			if (ASiCUtils.isASiCE(asicParameters)) {
				storeDocuments(manifestDocuments, zos);
			}

			storeDocuments(signatures, zos);
			storeSignedFiles(documentsToBeSigned, zos);
			storeMimetype(asicParameters, zos);
			storeZipComment(asicParameters, zos);

			zos.finish();

			return new InMemoryDocument(baos.toByteArray(), null, ASiCUtils.getMimeType(asicParameters));
		} catch (IOException e) {
			throw new DSSException("Unable to build the ASiC Container", e);
		}
	}

	private void storeDocuments(List<DSSDocument> documents, ZipOutputStream zos) throws IOException {
		for (DSSDocument doc : documents) {
			final ZipEntry entrySignature = new ZipEntry(doc.getName());
			zos.putNextEntry(entrySignature);
			doc.writeTo(zos);
		}
	}

	private void storeSignedFiles(final List<DSSDocument> detachedDocuments, final ZipOutputStream zos) throws IOException {
		for (DSSDocument detachedDocument : detachedDocuments) {
			try (InputStream is = detachedDocument.openStream()) {
				final String detachedDocumentName = detachedDocument.getName();
				final String name = detachedDocumentName != null ? detachedDocumentName : ZIP_ENTRY_DETACHED_FILE;
				final ZipEntry entryDocument = new ZipEntry(name);

				zos.setLevel(ZipEntry.DEFLATED);
				zos.putNextEntry(entryDocument);
				Utils.copy(is, zos);
			}
		}
	}

	private void storeMimetype(final ASiCParameters asicParameters, final ZipOutputStream zos) throws IOException {
		final byte[] mimeTypeBytes = ASiCUtils.getMimeTypeString(asicParameters).getBytes("UTF-8");
		final ZipEntry entryMimetype = getZipEntryMimeType(mimeTypeBytes);
		zos.putNextEntry(entryMimetype);
		Utils.write(mimeTypeBytes, zos);
	}

	private ZipEntry getZipEntryMimeType(final byte[] mimeTypeBytes) {
		final ZipEntry entryMimetype = new ZipEntry(ZIP_ENTRY_MIMETYPE);
		entryMimetype.setMethod(ZipEntry.STORED);
		entryMimetype.setSize(mimeTypeBytes.length);
		entryMimetype.setCompressedSize(mimeTypeBytes.length);
		final CRC32 crc = new CRC32();
		crc.update(mimeTypeBytes);
		entryMimetype.setCrc(crc.getValue());
		return entryMimetype;
	}

	protected void storeZipComment(final ASiCParameters asicParameters, final ZipOutputStream zos) {
		if (asicParameters.isZipComment()) {
			zos.setComment(ASiCUtils.MIME_TYPE_COMMENT + ASiCUtils.getMimeTypeString(asicParameters));
		}
	}

}