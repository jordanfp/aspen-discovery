package com.turning_leaf_technologies.cloud_library;

import com.turning_leaf_technologies.grouping.RecordGroupingProcessor;
import com.turning_leaf_technologies.grouping.RemoveRecordFromWorkResult;
import com.turning_leaf_technologies.indexing.RecordIdentifier;
import com.turning_leaf_technologies.net.NetworkUtils;
import com.turning_leaf_technologies.net.WebServiceResponse;
import com.turning_leaf_technologies.reindexer.GroupedWorkIndexer;
import org.apache.logging.log4j.Logger;
import org.ini4j.Ini;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;

public class CloudLibraryExporter {
	private final String serverName;
	private final Ini configIni;
	private final Logger logger;
	private final Connection aspenConn;
	private final long settingsId;
	private final Long startTimeForLogging;
	private final String baseUrl;
	private final String accountId;
	private final String accountKey;
	private final String libraryId;
	private final boolean doFullReload;
	private long lastExtractTime;
	private final long lastExtractTimeAll;
	private CloudLibraryExtractLogEntry logEntry;

	//SQL Statements
	private final PreparedStatement deleteCloudLibraryItemStmt;
	private final PreparedStatement getAllExistingCloudLibraryItemsStmt;
	private final PreparedStatement deleteCloudLibraryAvailabilityStmt;
	private final PreparedStatement cloudLibraryTitleHasAvailabilityStmt;

	//Record grouper
	private GroupedWorkIndexer groupedWorkIndexer;
	private RecordGroupingProcessor recordGroupingProcessorSingleton = null;

	//Existing records
	private static HashMap<String, CloudLibraryTitle> existingRecords = new HashMap<>();

	public CloudLibraryExporter(String serverName, Ini configIni, ResultSet getSettingsRS, Logger logger, Connection aspenConn) throws SQLException {
		this.serverName = serverName;
		this.configIni = configIni;
		this.logger = logger;
		this.aspenConn = aspenConn;

		settingsId = getSettingsRS.getLong("id");

		Date startTime = new Date();
		startTimeForLogging = startTime.getTime() / 1000;
		baseUrl = getSettingsRS.getString("apiUrl");
		accountId = getSettingsRS.getString("accountId");
		accountKey = getSettingsRS.getString("accountKey");
		libraryId = getSettingsRS.getString("libraryId");

		doFullReload = getSettingsRS.getBoolean("runFullUpdate");
		lastExtractTime = getSettingsRS.getLong("lastUpdateOfChangedRecords");
		lastExtractTimeAll = getSettingsRS.getLong("lastUpdateOfAllRecords");

		deleteCloudLibraryItemStmt = aspenConn.prepareStatement("UPDATE cloud_library_title SET deleted = 1 where id = ?");
		deleteCloudLibraryAvailabilityStmt = aspenConn.prepareStatement("DELETE FROM cloud_library_availability where id = ?");
		cloudLibraryTitleHasAvailabilityStmt = aspenConn.prepareStatement("SELECT count(*) as numAvailability FROM cloud_library_availability where id = ?");
		getAllExistingCloudLibraryItemsStmt = aspenConn.prepareStatement("SELECT cloud_library_title.id, cloud_library_title.cloudLibraryId, cloud_library_title.rawChecksum, deleted, cloud_library_availability.id as availabilityId from cloud_library_title left join cloud_library_availability on cloud_library_availability.cloudLibraryId = cloud_library_title.cloudLibraryId where settingId = ?");

		createDbLogEntry(settingsId, startTime, aspenConn);
	}

	public int extractRecords(){
		int numChanges = 0;
		String startDate = "2000-01-01";
		if (!doFullReload) {
			lastExtractTime = Math.max(lastExtractTime, lastExtractTimeAll);

			SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));
			startDate = dateFormatter.format(new Date(lastExtractTime * 1000));
		}

		//Get a list of all existing records in the database
		loadExistingTitles(settingsId);

		CloudLibraryMarcHandler handler = new CloudLibraryMarcHandler(this, settingsId, existingRecords, doFullReload, startTimeForLogging, aspenConn, getRecordGroupingProcessor(), getGroupedWorkIndexer(), logEntry, logger);

		int curOffset = 1;
		boolean moreRecords = true;
		while (moreRecords) {
			moreRecords = false;
			//Get a list of eBooks and eAudiobooks to process
			String apiPath = "/cirrus/library/" + libraryId + "/data/marc?offset=" + curOffset + "&limit=50&startdate=" + startDate;

			//noinspection ConstantConditions
			for (int curTry = 1; curTry <= 4; curTry++) {
				WebServiceResponse response = callCloudLibrary(apiPath);
				if (response == null) {
					//Something really bad happened, we're done.
					return numChanges;
				} else if (!response.isSuccess()) {
					if (response.getResponseCode() != 502) {
						logEntry.incErrors("Error " + response.getResponseCode() + " calling " + apiPath + ": " + response.getMessage());
						break;
					} else {
						if (curTry == 4) {
							logEntry.incErrors("Error " + response.getResponseCode() + " calling " + apiPath + ": " + response.getMessage());
							logEntry.addNote(response.getMessage());
							break;
						} else {
							try {
								Thread.sleep(1000);
							} catch (InterruptedException e) {
								logger.error("Thread was interrupted while waiting to retry for cloud library");
							}
						}
					}
				} else {
					try {
						SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
						SAXParser saxParser = saxParserFactory.newSAXParser();
						saxParser.parse(new ByteArrayInputStream(response.getMessage().getBytes(StandardCharsets.UTF_8)), handler);

						if (handler.getNumDocuments() > 0) {
							curOffset += handler.getNumDocuments();
							numChanges += handler.getNumDocuments();
							moreRecords = true;
						}
						logEntry.saveResults();
					} catch (SAXException | ParserConfigurationException | IOException e) {
						logger.error("Error parsing response", e);
						logEntry.addNote("Error parsing response: " + e.toString());
					}
					break;
				}
			}
		}

		//Handle events to determine status changes when the bibs don't change.
		if (!doFullReload) {
			String eventsApiPath = "/cirrus/library/" + libraryId + "/data/cloudevents?startdate=" + startDate;
			CloudLibraryEventHandler eventHandler = new CloudLibraryEventHandler(this, doFullReload, startTimeForLogging, aspenConn, getRecordGroupingProcessor(), getGroupedWorkIndexer(), logEntry, logger);
			//noinspection ConstantConditions
			for (int curTry = 1; curTry <= 4; curTry++) {
				WebServiceResponse response = callCloudLibrary(eventsApiPath);
				if (response == null) {
					//Something really bad happened, we're done.
					return numChanges;
				} else if (!response.isSuccess()) {
					if (response.getResponseCode() != 502) {
						logEntry.incErrors("Error " + response.getResponseCode() + " calling " + eventsApiPath + ": " + response.getMessage());
						break;
					} else {
						if (curTry == 4) {
							logEntry.incErrors("Error " + response.getResponseCode() + " calling " + eventsApiPath + ": " + response.getMessage());
							break;
						} else {
							try {
								Thread.sleep(1000);
							} catch (InterruptedException e) {
								logger.error("Thread was interrupted while waiting to retry for cloud library");
							}
						}
					}
				} else {
					try {
						SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
						SAXParser saxParser = saxParserFactory.newSAXParser();
						saxParser.parse(new ByteArrayInputStream(response.getMessage().getBytes(StandardCharsets.UTF_8)), eventHandler);

						if (handler.getNumDocuments() > 0) {
							numChanges += handler.getNumDocuments();
						}
						logEntry.saveResults();
					} catch (SAXException | ParserConfigurationException | IOException e) {
						logger.error("Error parsing response", e);
						logEntry.addNote("Error parsing response: " + e.toString());
					}
					break;
				}
			}
		}

		if (doFullReload && !logEntry.hasErrors()) {
			try {
				//Un mark that a full update needs to be done
				PreparedStatement updateSettingsStmt = aspenConn.prepareStatement("UPDATE cloud_library_settings set runFullUpdate = 0 where id = ?");
				updateSettingsStmt.setLong(1, settingsId);
				updateSettingsStmt.executeUpdate();
			}catch (Exception sqle){
				logEntry.incErrors("Could not update cloud library settings to disable run full update", sqle);
			}

			//Mark any records that no longer exist in search results as deleted, but only if we are doing a full update
			numChanges += deleteItems();
		}

		//Update the last time we ran the update in settings.  This is always done since Cloud Library has some expected errors.
		PreparedStatement updateExtractTime;
		String columnToUpdate = "lastUpdateOfChangedRecords";
		if (doFullReload) {
			columnToUpdate = "lastUpdateOfAllRecords";
		}
		try {
			updateExtractTime = aspenConn.prepareStatement("UPDATE cloud_library_settings set " + columnToUpdate + " = ? WHERE id = ?");
			updateExtractTime.setLong(1, startTimeForLogging);
			updateExtractTime.setLong(2, settingsId);
			updateExtractTime.executeUpdate();
		}catch (Exception sqle){
			logEntry.incErrors("Could not update cloud library settings to set last update time", sqle);
		}

		logger.info("Updated or added " + numChanges + " records");

		//For any records that have been marked to reload, regroup and reindex the records
		processRecordsToReload(logEntry);

		if (recordGroupingProcessorSingleton != null) {
			recordGroupingProcessorSingleton.close();
			recordGroupingProcessorSingleton = null;
		}

		if (groupedWorkIndexer != null) {
			groupedWorkIndexer.finishIndexingFromExtract(logEntry);
			groupedWorkIndexer.close();
			groupedWorkIndexer = null;
			existingRecords = null;
		}

		if (logEntry.hasErrors()) {
			logger.error("There were errors during the export!");
		}

		logger.info("Finished " + new Date().toString());
		long endTime = new Date().getTime();
		long elapsedTime = (endTime / 1000) - startTimeForLogging;
		logger.info("Elapsed Minutes " + (elapsedTime / 60));

		logEntry.setFinished();

		return numChanges;
	}

	private void createDbLogEntry(long settingsId, Date startTime, Connection aspenConn) {
		//Remove log entries older than 45 days
		long earliestLogToKeep = (startTime.getTime() / 1000) - (60 * 60 * 24 * 45);
		try {
			int numDeletions = aspenConn.prepareStatement("DELETE from cloud_library_export_log WHERE startTime < " + earliestLogToKeep).executeUpdate();
			logger.info("Deleted " + numDeletions + " old log entries");
		} catch (SQLException e) {
			logger.error("Error deleting old log entries", e);
		}

		//Start a log entry
		logEntry = new CloudLibraryExtractLogEntry(aspenConn, settingsId, logger);
	}

	private int deleteItems() {
		int numDeleted = 0;
		try {
			for (CloudLibraryTitle cloudLibraryTitle : existingRecords.values()) {
				if (!cloudLibraryTitle.isDeleted()) {
					//Make sure that the title does not have copies in another collection
					if (cloudLibraryTitle.getAvailabilityId() != null){
						deleteCloudLibraryAvailabilityStmt.setLong(1, cloudLibraryTitle.getAvailabilityId());
						deleteCloudLibraryAvailabilityStmt.executeUpdate();
					}
					cloudLibraryTitleHasAvailabilityStmt.setLong(1, cloudLibraryTitle.getId());
					ResultSet cloudLibraryTitleHasAvailabilityRS = cloudLibraryTitleHasAvailabilityStmt.executeQuery();
					boolean deleteTitle = true;
					if (cloudLibraryTitleHasAvailabilityRS.next()){
						int numAvailability = cloudLibraryTitleHasAvailabilityRS.getInt("numAvailability");
						if (numAvailability > 0){
							deleteTitle = false;
						}
					}

					if (deleteTitle) {
						deleteCloudLibraryItemStmt.setLong(1, cloudLibraryTitle.getId());
						deleteCloudLibraryItemStmt.executeUpdate();
						RemoveRecordFromWorkResult result = getRecordGroupingProcessor().removeRecordFromGroupedWork("cloud_library", cloudLibraryTitle.getCloudLibraryId());
						if (result.reindexWork) {
							getGroupedWorkIndexer().processGroupedWork(result.permanentId);
						} else if (result.deleteWork) {
							//Delete the work from solr and the database
							getGroupedWorkIndexer().deleteRecord(result.permanentId);
						}
					}else{
						//We need to reindex the record to make sure that the availability changes.
					}

					numDeleted++;
					logEntry.incDeleted();
				}
			}
			if (numDeleted > 0) {
				logEntry.saveResults();
				logger.warn("Deleted " + numDeleted + " old titles");
			}
		} catch (SQLException e) {
			logger.error("Error deleting items", e);
			logEntry.addNote("Error deleting items " + e.toString());
		}
		return numDeleted;
	}

	private void loadExistingTitles(long settingId) {
		try {
			if (existingRecords == null) existingRecords = new HashMap<>();
			getAllExistingCloudLibraryItemsStmt.setLong(1, settingId);
			ResultSet allRecordsRS = getAllExistingCloudLibraryItemsStmt.executeQuery();
			while (allRecordsRS.next()) {
				String cloudLibraryId = allRecordsRS.getString("cloudLibraryId");
				CloudLibraryTitle newTitle = new CloudLibraryTitle(
						allRecordsRS.getLong("id"),
						cloudLibraryId,
						allRecordsRS.getLong("rawChecksum"),
						allRecordsRS.getBoolean("deleted"),
						allRecordsRS.getLong("availabilityId")
				);
				existingRecords.put(cloudLibraryId, newTitle);
			}
		} catch (SQLException e) {
			logger.error("Error loading existing titles", e);
			logEntry.addNote("Error loading existing titles" + e.toString());
			System.exit(-1);
		}
	}

	private void processRecordsToReload(CloudLibraryExtractLogEntry logEntry) {
		try {
			PreparedStatement getRecordsToReloadStmt = aspenConn.prepareStatement("SELECT * from record_identifiers_to_reload WHERE processed = 0 and type='cloud_library'", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
			PreparedStatement markRecordToReloadAsProcessedStmt = aspenConn.prepareStatement("UPDATE record_identifiers_to_reload SET processed = 1 where id = ?");
			PreparedStatement getItemDetailsForRecordStmt = aspenConn.prepareStatement("SELECT title, subTitle, author, format from cloud_library_title where cloudLibraryId = ?", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

			ResultSet getRecordsToReloadRS = getRecordsToReloadStmt.executeQuery();
			int numRecordsToReloadProcessed = 0;
			while (getRecordsToReloadRS.next()){
				long recordToReloadId = getRecordsToReloadRS.getLong("id");
				String cloudLibraryId = getRecordsToReloadRS.getString("identifier");
				//Regroup the record
				getItemDetailsForRecordStmt.setString(1, cloudLibraryId);
				ResultSet getItemDetailsForRecordRS = getItemDetailsForRecordStmt.executeQuery();
				if (getItemDetailsForRecordRS.next()){
					String title = getItemDetailsForRecordRS.getString("title");
					String subTitle = getItemDetailsForRecordRS.getString("subTitle");
					String author = getItemDetailsForRecordRS.getString("author");
					String format = getItemDetailsForRecordRS.getString("format");
					RecordIdentifier primaryIdentifier = new RecordIdentifier("cloud_library", cloudLibraryId);


					String groupedWorkId = getRecordGroupingProcessor().processRecord(primaryIdentifier, title, subTitle, author, format, true);
					//Reindex the record
					getGroupedWorkIndexer().processGroupedWork(groupedWorkId);

					markRecordToReloadAsProcessedStmt.setLong(1, recordToReloadId);
					markRecordToReloadAsProcessedStmt.executeUpdate();
					numRecordsToReloadProcessed++;
				}else{
					logEntry.incErrors("Could not get details for record to reload " + cloudLibraryId);
				}
				getItemDetailsForRecordRS.close();
			}
			if (numRecordsToReloadProcessed > 0){
				logEntry.addNote("Regrouped " + numRecordsToReloadProcessed + " records marked for reprocessing");
			}
			getRecordsToReloadRS.close();
		}catch (Exception e){
			logEntry.incErrors("Error processing records to reload " + e.toString());
		}
	}

	private GroupedWorkIndexer getGroupedWorkIndexer() {
		if (groupedWorkIndexer == null) {
			groupedWorkIndexer = new GroupedWorkIndexer(serverName, aspenConn, configIni, false, false, logEntry, logger);
		}
		return groupedWorkIndexer;
	}

	private RecordGroupingProcessor getRecordGroupingProcessor() {
		if (recordGroupingProcessorSingleton == null) {
			recordGroupingProcessorSingleton = new RecordGroupingProcessor(aspenConn, serverName, logEntry, logger);
		}
		return recordGroupingProcessorSingleton;
	}

	CloudLibraryAvailability loadAvailabilityForRecord(String cloudLibraryId) {
		CloudLibraryAvailability availability = new CloudLibraryAvailability();
		String apiPath = "/cirrus/library/" + libraryId + "/item/summary/" + cloudLibraryId;

		WebServiceResponse response = callCloudLibrary(apiPath);
		if (response == null) {
			//Something really bad happened, we're done.
			return null;
		} else if (!response.isSuccess()) {
			if (response.getResponseCode() != 500) {
				logEntry.incErrors("Error " + response.getResponseCode() + " calling " + apiPath + ": " + response.getMessage());
			}
			logEntry.addNote("Error getting availability from " + apiPath + ": " + response.getResponseCode() + " " + response.getMessage());
			return null;
		} else {
			availability.setRawResponse(response.getMessage());
			CloudLibraryAvailabilityHandler handler = new CloudLibraryAvailabilityHandler(availability);

			try {
				SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
				SAXParser saxParser = saxParserFactory.newSAXParser();
				saxParser.parse(new ByteArrayInputStream(response.getMessage().getBytes(StandardCharsets.UTF_8)), handler);
			} catch (SAXException | ParserConfigurationException | IOException e) {
				logger.error("Error parsing response", e);
				logEntry.addNote("Error parsing response: " + e.toString());
			}
		}

		return availability;
	}

	private WebServiceResponse callCloudLibrary(String apiPath) {
		String bookUrl = baseUrl + apiPath;
		HashMap<String, String> headers = new HashMap<>();
		SimpleDateFormat dateFormatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
		dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));
		String formattedDate = dateFormatter.format(new Date());

		String dataToSign = formattedDate + "\nGET\n" + apiPath;
		String signature;
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("hmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(accountKey.getBytes(), "HmacSHA1"));
			mac.update(dataToSign.getBytes());
			signature = Base64.getEncoder().encodeToString(mac.doFinal());
		} catch (NoSuchAlgorithmException noSuchAlgorithmException) {
			logger.error("No algorithm found when creating signature", noSuchAlgorithmException);
			return null;
		} catch (InvalidKeyException e) {
			logger.error("Invalid Key", e);
			return null;
		}

		headers.put("3mcl-Datetime", formattedDate);
		headers.put("3mcl-Authorization", "3MCLAUTH " + accountId + ":" + signature);
		headers.put("3mcl-APIVersion", "3.0");
		return NetworkUtils.getURL(bookUrl, logger, headers);
	}
}
