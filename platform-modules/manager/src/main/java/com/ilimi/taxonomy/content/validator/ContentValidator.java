package com.ilimi.taxonomy.content.validator;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.Tika;

import com.ilimi.common.exception.ClientException;
import com.ilimi.common.exception.ServerException;
import com.ilimi.graph.dac.model.Node;
import com.ilimi.taxonomy.content.common.ContentErrorMessageConstants;
import com.ilimi.taxonomy.content.enums.ContentErrorCodeConstants;
import com.ilimi.taxonomy.content.enums.ContentWorkflowPipelineParams;
import com.ilimi.taxonomy.content.util.PropertiesUtil;

public class ContentValidator {

	private static Logger LOGGER = LogManager.getLogger(ContentValidator.class.getName());

	private static final String DEF_CONTENT_PACKAGE_MIME_TYPE = "application/zip";

	public boolean isValidContentPackage(File file) {
		boolean isValidContentPackage = false;
		try {
			if (file.exists()) {
				LOGGER.info("Validating File: " + file.getName());
				if (!isValidContentMimeType(file))
					throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
							ContentErrorMessageConstants.INVALID_CONTENT_PACKAGE_FILE_MIME_TYPE_ERROR
									+ " | [The uploaded package is invalid.]");
				if (!isValidContentPackageStructure(file))
					throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
							ContentErrorMessageConstants.INVALID_CONTENT_PACKAGE_STRUCTURE_ERROR
									+ " | ['index' file and other other folders (assets, data & widgets) should be at root location.]");
				if (!isValidContentSize(file))
					throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
							ContentErrorMessageConstants.INVALID_CONTENT_PACKAGE_SIZE_ERROR
									+ " | [Too large Content Package file size.]");

				isValidContentPackage = true;
			}
		} catch (ClientException ce) {
			throw ce;
		} catch (IOException e) {
			throw new ServerException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
					ContentErrorMessageConstants.CONTENT_PACKAGE_FILE_OPERATION_ERROR
							+ " | [Something went wrong while processing the Package file.]",
					e);
		} catch (Exception e) {
			throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
					ContentErrorMessageConstants.CONTENT_PACKAGE_VALIDATOR_ERROR
							+ " | [Something went wrong while validating the Package file.]",
					e);
		}
		LOGGER.info("Is Valid Content Package File ? : " + isValidContentPackage);
		return isValidContentPackage;
	}

	public boolean isValidContentNode(Node node) {
		boolean isValidContentNode = false;
		try {
			if (null != node) {
				LOGGER.info("Validating Content Node: " + node.getIdentifier());
				if (null == node.getMetadata())
					throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
							ContentErrorMessageConstants.INVALID_CONTENT_METADATA + " | [Invalid Metadata.]");
				if (!isAllRequiredFieldsAvailable(node))
					throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
							ContentErrorMessageConstants.MISSING_REQUIRED_FIELDS + " | [Content of Mime-Type '"
									+ node.getMetadata().get(ContentWorkflowPipelineParams.mimeType.name())
									+ "' require few mandatory fields for further processing.]");

				isValidContentNode = true;
			}
		} catch (ClientException e) {
			throw e;
		} catch (ServerException e) {
			throw e;
		} catch (Exception e) {
			throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
					ContentErrorMessageConstants.CONTENT_NODE_VALIDATION_ERROR
							+ " | [Something went wrong while Content Object validation Check.",
					e);
		}
		LOGGER.info("Is Valid Content Node ? : " + isValidContentNode);
		return isValidContentNode;
	}

	private boolean isValidContentMimeType(File file) throws IOException {
		boolean isValidMimeType = false;
		if (file.exists()) {
			LOGGER.info("Validating File For MimeType: " + file.getName());
			Tika tika = new Tika();
			String mimeType = tika.detect(file);
			isValidMimeType = StringUtils.equalsIgnoreCase(DEF_CONTENT_PACKAGE_MIME_TYPE, mimeType);
		}
		return isValidMimeType;
	}

	private boolean isValidContentSize(File file) {
		boolean isValidSize = false;
		if (file.exists()) {
			LOGGER.info("Validating File For Size: " + file.getName());
			if (file.length() <= getContentPackageFileSizeLimit())
				isValidSize = true;
		}
		return isValidSize;
	}

	private double getContentPackageFileSizeLimit() {
		double size = 52428800; // In Bytes, Default is 50MB
		String limit = PropertiesUtil
				.getProperty(ContentWorkflowPipelineParams.MAX_CONTENT_PACKAGE_FILE_SIZE_LIMIT.name());
		if (!StringUtils.isBlank(limit)) {
			try {
				size = Double.parseDouble(limit);
			} catch (Exception e) {
			}
		}
		return size;
	}

	@SuppressWarnings("resource")
	private boolean isValidContentPackageStructure(File file) throws IOException {
		final String JSON_ECML_FILE_NAME = "index.json";
		final String XML_ECML_FILE_NAME = "index.ecml";
		boolean isValidPackage = false;
		if (file.exists()) {
			LOGGER.info("Validating File For Folder Structure: " + file.getName());
			ZipFile zipFile = new ZipFile(file);
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (StringUtils.equalsIgnoreCase(entry.getName(), JSON_ECML_FILE_NAME)
						|| StringUtils.equalsIgnoreCase(entry.getName(), XML_ECML_FILE_NAME)) {
					isValidPackage = true;
					break;
				}
			}
		}
		return isValidPackage;
	}

	private boolean isAllRequiredFieldsAvailable(Node node) {
		boolean isValid = false;
		if (null != node) {
			String mimeType = (String) node.getMetadata().get(ContentWorkflowPipelineParams.mimeType.name());
			if (StringUtils.isNotBlank(mimeType)) {
				LOGGER.info("Checking Required Fields For: " + mimeType);
				switch (mimeType) {
				case "application/vnd.ekstep.ecml-archive":
					// Either 'body' or 'artifactUrl' is needed
					if (StringUtils
							.isNotBlank((String) node.getMetadata().get(ContentWorkflowPipelineParams.body.name()))
							|| StringUtils.isNotBlank(
									(String) node.getMetadata().get(ContentWorkflowPipelineParams.artifactUrl.name())))
						isValid = true;
					else
						throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
								ContentErrorMessageConstants.MISSING_REQUIRED_FIELDS
										+ " | [Either 'body' or 'artifactUrl' are required for processing of ECML content]");
					break;

				case "application/vnd.ekstep.html-archive":
					// 'artifactUrl is needed'
					if (StringUtils.isNotBlank(
							(String) (node.getMetadata().get(ContentWorkflowPipelineParams.artifactUrl.name()))))
						isValid = true;
					else
						throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
								ContentErrorMessageConstants.MISSING_REQUIRED_FIELDS
										+ " | [HTML archive should be uploaded for further processing of HTML content]");
					break;

				case "application/vnd.android.package-archive":
					if (StringUtils.isNotBlank(
							(String) (node.getMetadata().get(ContentWorkflowPipelineParams.artifactUrl.name()))))
						isValid = true;
					else
						throw new ClientException(ContentErrorCodeConstants.VALIDATOR_ERROR.name(),
								ContentErrorMessageConstants.MISSING_REQUIRED_FIELDS
										+ " | [APK file should be uploaded for further processing of APK content]");
					break;

				case "application/vnd.ekstep.content-collection":
					isValid = true;
					break;

				case "assets":
					isValid = true;
					break;

				default:
					LOGGER.info("Invalid Mime-Type: " + mimeType);
					break;
				}
			}
		}
		return isValid;
	}

}
