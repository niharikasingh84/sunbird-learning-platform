package org.ekstep.content.concrete.processor;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.ekstep.common.util.HttpDownloadUtility;
import org.ekstep.common.util.S3PropertyReader;
import org.ekstep.content.common.ContentErrorMessageConstants;
import org.ekstep.content.entity.Manifest;
import org.ekstep.content.entity.Media;
import org.ekstep.content.entity.Plugin;
import org.ekstep.content.enums.ContentErrorCodeConstants;
import org.ekstep.content.enums.ContentWorkflowPipelineParams;
import org.ekstep.content.processor.AbstractProcessor;
import org.ekstep.content.util.PropertiesUtil;

import com.ilimi.common.exception.ClientException;
import com.ilimi.common.exception.ServerException;
import com.ilimi.common.util.ILogger;
import com.ilimi.common.util.PlatformLogManager;
import com.ilimi.common.util.PlatformLogger;

/**
 * The Class LocalizeAssetProcessor is a Content Workflow pipeline Processor
 * Which is responsible for downloading of Asset Items for the Storage Space to
 * the local storage.
 * 
 * It also has the capability of retry download in case of failure for the
 * particular amount of retry count.
 * 
 * @author Mohammad Azharuddin
 * 
 * @see AssessmentItemCreatorProcessor
 * @see AssetCreatorProcessor
 * @see AssetsValidatorProcessor
 * @see BaseConcreteProcessor
 * @see EmbedControllerProcessor
 * @see GlobalizeAssetProcessor
 * @see MissingAssetValidatorProcessor
 * @see MissingControllerValidatorProcessor
 * 
 */
public class LocalizeAssetProcessor extends AbstractProcessor {

	/** The logger. */
	private static ILogger LOGGER = PlatformLogManager.getLogger();

	/**
	 * Instantiates a new localize asset processor and sets the base path andS
	 * current content id for further processing.
	 *
	 * @param basePath
	 *            the base path is the location for content package file
	 *            handling and all manipulations.
	 * @param contentId
	 *            the content id is the identifier of content for which the
	 *            Processor is being processed currently.
	 */
	public LocalizeAssetProcessor(String basePath, String contentId) {
		if (!isValidBasePath(basePath))
			throw new ClientException(ContentErrorCodeConstants.INVALID_PARAMETER.name(),
					ContentErrorMessageConstants.INVALID_CWP_CONST_PARAM + " | [Path does not Exist.]");
		if (StringUtils.isBlank(contentId))
			throw new ClientException(ContentErrorCodeConstants.INVALID_PARAMETER.name(),
					ContentErrorMessageConstants.INVALID_CWP_CONST_PARAM + " | [Invalid Content Id.]");
		this.basePath = basePath;
		this.contentId = contentId;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * com.ilimi.taxonomy.content.processor.AbstractProcessor#process(com.ilimi.
	 * taxonomy.content.entity.Plugin)
	 */
	@Override
	protected Plugin process(Plugin plugin) {
		try {
			if (null != plugin) {
				List<Media> medias = getMedia(plugin);
				Map<String, String> downloadedAssetsMap = processAssetsDownload(medias);
				Manifest manifest = plugin.getManifest();
				if (null != manifest)
					manifest.setMedias(getUpdatedMediaWithUrl(downloadedAssetsMap, getMedia(plugin)));
			}
		} catch (ClientException e) {
			throw e;
		} catch (ServerException e) {
			throw e;
		} catch (Exception e) {
			throw new ServerException(ContentErrorCodeConstants.PROCESSOR_ERROR.name(),
					ContentErrorMessageConstants.PROCESSOR_ERROR + " | [LocalizeAssetProcessor]", e);
		}

		return plugin;
	}

	/**
	 * <code>processAssetsDownload</code> is the method responsible for start
	 * the Asset Download, It also handles the retry mechanism of download where
	 * the retry count is coming from the configuration.
	 *
	 * @param medias
	 *            the medias is <code>list</code> of <code>medias</code> from
	 *            <code>ECRF Object</code>.
	 * @return the map of <code>asset Id</code> and asset <code>file name</code>
	 *         .
	 */
	@SuppressWarnings("unchecked")
	private Map<String, String> processAssetsDownload(List<Media> medias) {
		LOGGER.log("Medias to Download: ", medias);
		Map<String, String> map = new HashMap<String, String>();
		try {
			LOGGER.log("Total Medias to Download: for [Content Id '" + contentId + "']", medias.size());

			Map<String, Object> downloadResultMap = downloadAssets(medias);
			LOGGER.log("Downloaded Result Map After the Firts Try: ",
					downloadResultMap + " | [Content Id '" + contentId + "']");

			Map<String, String> successMap = (Map<String, String>) downloadResultMap
					.get(ContentWorkflowPipelineParams.success.name());
			LOGGER.log("Successful Media Downloads: " + successMap + " | [Content Id '" + contentId + "']");
			if (null != successMap && !successMap.isEmpty())
				map.putAll(successMap);

			List<Media> skippedMedia = (List<Media>) downloadResultMap
					.get(ContentWorkflowPipelineParams.skipped.name());
			LOGGER.log("Skipped Media Downloads: " + skippedMedia + " | [Content Id '" + contentId + "']");
			if (null != skippedMedia && !skippedMedia.isEmpty()) {
				LOGGER.log("Fetching the Retry Count From Configuration. | [Content Id '" + contentId + "']");
				String retryCount = PropertiesUtil
						.getProperty(ContentWorkflowPipelineParams.RETRY_ASSET_DOWNLOAD_COUNT.name());
				if (!StringUtils.isBlank(retryCount)) {
					int retryCnt = NumberUtils.createInteger(retryCount);
					LOGGER.log("Starting the Retry For Count: " + retryCnt + " | [Content Id '" + contentId + "']");
					for (int i = 0; i < retryCnt; i++) {
						LOGGER.log("Retrying Asset Download For " + i + 1 + " times" + " | [Content Id '" + contentId
								+ "']");
						if (null != skippedMedia && !skippedMedia.isEmpty()) {
							Map<String, Object> result = downloadAssets(skippedMedia);
							Map<String, String> successfulDownloads = (Map<String, String>) result
									.get(ContentWorkflowPipelineParams.success.name());
							skippedMedia = (List<Media>) downloadResultMap
									.get(ContentWorkflowPipelineParams.skipped.name());
							if (null != successfulDownloads && !successfulDownloads.isEmpty())
								map.putAll(successfulDownloads);
						}
					}
				}
			}
		} catch (InterruptedException | ExecutionException e) {
			throw new ServerException(ContentErrorCodeConstants.PROCESSOR_CONC_OP_ERROR.name(),
					ContentErrorMessageConstants.ASSET_CONCURRENT_DOWNLOAD_ERROR, e);
		}
		return map;
	}

	/**
	 * <code>downloadAssets</code> is the Utility method which tries to download
	 * the given <code>medias</code>.
	 *
	 * @param medias
	 *            the medias is a <code>list</code> of <code>Medias</code> from
	 *            <code>ECRF Object</code>.
	 * @return the map of downloaded and skipped medias map.
	 * @throws InterruptedException
	 *             the interrupted exception is thrown when the concurrent
	 *             execution is interrupted.
	 * @throws ExecutionException
	 *             the execution exception is thrown when there is an issue in
	 *             running the Current Future Task.
	 */
	private Map<String, Object> downloadAssets(List<Media> medias) throws InterruptedException, ExecutionException {
		Map<String, Object> map = new HashMap<String, Object>();
		if (null != medias && !StringUtils.isBlank(basePath)) {
			LOGGER.log("Starting Asset Download Fanout. | [Content Id '" + contentId + "']", contentId);
			final List<Media> skippedMediaDownloads = new ArrayList<Media>();
			final Map<String, String> successfulMediaDownloads = new HashMap<String, String>();
			ExecutorService pool = Executors.newFixedThreadPool(10);
			List<Callable<Map<String, String>>> tasks = new ArrayList<Callable<Map<String, String>>>(medias.size());
			for (final Media media : medias) {
				tasks.add(new Callable<Map<String, String>>() {
					public Map<String, String> call() throws Exception {
						Map<String, String> downloadMap = new HashMap<String, String>();
						if (!StringUtils.isBlank(media.getSrc()) && !StringUtils.isBlank(media.getType())) {
							String downloadPath = basePath;
							if (isWidgetTypeAsset(media.getType()))
								downloadPath += File.separator + ContentWorkflowPipelineParams.widgets.name();
							else
								downloadPath += File.separator + ContentWorkflowPipelineParams.assets.name();
							
							String subFolder = "";
							if(!media.getSrc().startsWith("http")) {
								File f = new File(media.getSrc());
								subFolder = f.getParent();
								if(f.exists()){
									f.delete();
								}
								subFolder = StringUtils.stripStart(subFolder, File.separator);
							}
							if (StringUtils.isNotBlank(subFolder))
								downloadPath += File.separator + subFolder;
							createDirectoryIfNeeded(downloadPath);
							File downloadedFile = HttpDownloadUtility.downloadFile(getDownloadUrl(media.getSrc()), downloadPath);
							LOGGER.log("Downloaded file : " + media.getSrc() + " - " + downloadedFile
									+ " | [Content Id '" + contentId + "']");
							if (null == downloadedFile)
								skippedMediaDownloads.add(media);
							else {
								if (StringUtils.isNotBlank(subFolder))
									downloadMap.put(media.getId(), subFolder + File.separator + downloadedFile.getName());
								else
									downloadMap.put(media.getId(), downloadedFile.getName());
							}
						}
						return downloadMap;
					}
				});
			}
			List<Future<Map<String, String>>> results = pool.invokeAll(tasks);
			for (Future<Map<String, String>> downloadMap : results) {
				Map<String, String> m = downloadMap.get();
				if (null != m)
					successfulMediaDownloads.putAll(m);
			}
			pool.shutdown();
			LOGGER.log("Successful Media Download Count for | [Content Id '"
					+ contentId + "']", successfulMediaDownloads.size());
			LOGGER.log("Skipped Media Download Count: | [Content Id '" + contentId
					+ "']", skippedMediaDownloads.size());
			map.put(ContentWorkflowPipelineParams.success.name(), successfulMediaDownloads);
			map.put(ContentWorkflowPipelineParams.skipped.name(), skippedMediaDownloads);
		}
		LOGGER.log("Returning the Map of Successful and Skipped Media. | [Content Id '" + contentId + "']", map.keySet());
		return map;
	}
	
	private String getDownloadUrl(String src) {
		if (StringUtils.isNotBlank(src)) {
			String env = S3PropertyReader.getProperty("s3.env");
			String prefix = "";
			LOGGER.log("Fetching s3 url from properties file fro environment:" , env);
			prefix = S3PropertyReader.getProperty("s3.url."+env);
			LOGGER.log("Fetching envioronment URL from properties file" , prefix);
			if (!src.startsWith("http"))
				src = prefix + src;
		}
		LOGGER.log("Returning src url" , src);
		return src;
	}

}
