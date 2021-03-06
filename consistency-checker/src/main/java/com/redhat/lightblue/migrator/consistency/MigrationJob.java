package com.redhat.lightblue.migrator.consistency;

import static com.redhat.lightblue.client.expression.query.NaryLogicalQuery.and;
import static com.redhat.lightblue.client.expression.query.NaryLogicalQuery.or;
import static com.redhat.lightblue.client.expression.query.ValueQuery.withValue;
import static com.redhat.lightblue.client.projection.FieldProjection.includeFieldRecursively;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.lightblue.client.LightblueClient;
import com.redhat.lightblue.client.enums.SortDirection;
import com.redhat.lightblue.client.expression.query.Query;
import com.redhat.lightblue.client.expression.query.ValueQuery;
import com.redhat.lightblue.client.http.LightblueHttpClient;
import com.redhat.lightblue.client.request.LightblueRequest;
import com.redhat.lightblue.client.request.SortCondition;
import com.redhat.lightblue.client.request.data.DataFindRequest;
import com.redhat.lightblue.client.request.data.DataSaveRequest;
import com.redhat.lightblue.client.response.LightblueResponse;

public class MigrationJob implements Runnable {

	private static final Logger LOGGER = LoggerFactory.getLogger(MigrationJob.class);

	public MigrationJob() {

	}

	public MigrationJob(MigrationConfiguration migrationConfiguration) {
		this.migrationConfiguration = migrationConfiguration;
	}

	private String sourceConfigPath;
	private String destinationConfigPath;
	
	private LightblueClient sourceClient;
	private LightblueClient destinationClient;
	
	// configuration for migrator
	private MigrationConfiguration migrationConfiguration;

	private List<MigrationJobRun> jobRuns;

	MigrationJobRun currentRun;
	
	// information about migrator instance working job
	private String owner;
	private String hostName;
	private String pid;

	// dates of data to migrate
	private Date startDate;
	private Date endDate;

	// when this job can be worked by migrator
	private Date whenAvailable;

	// how long we think it will take
	private int expectedRunTime;

	private boolean hasInconsistentDocuments;

	public MigrationConfiguration getJobConfiguration() {
		return migrationConfiguration;
	}

	public void setJobConfiguration(MigrationConfiguration jobConfiguration) {
		this.migrationConfiguration = jobConfiguration;
	}

	private List<MigrationJobRun> getJobRuns() {
		if (null == jobRuns) {
			jobRuns = new ArrayList<>(1);
		}
		return jobRuns;
	}
	
	public void setJobRuns(List<MigrationJobRun> jobRuns) {
		this.jobRuns = jobRuns;
  }
	
	public LightblueClient getSourceClient() {
		return sourceClient;
	}

	public void setSourceClient(LightblueClient client) {
		this.sourceClient = client;
	}

	public LightblueClient getDestinationClient() {
		return destinationClient;
	}

	public void setDestinationClient(LightblueClient client) {
		this.destinationClient = client;
	}

	public void setOverwriteDestinationDocuments(boolean overwriteDestinationDocuments) {
		migrationConfiguration.setOverwriteDestinationDocuments(overwriteDestinationDocuments);
	}

	public boolean shouldOverwriteDestinationDocuments() {
		return migrationConfiguration.shouldOverwriteDestinationDocuments();
	}

	public String getSourceConfigPath() {
		return sourceConfigPath;
	}

	public void setSourceConfigPath(String configPath) {
		this.sourceConfigPath = configPath;
	}
	
	public String getDestinationConfigPath() {
		return destinationConfigPath;
	}

	public void setDestinationConfigPath(String configPath) {
		this.destinationConfigPath = configPath;
	}
	
	/**
	 * Returns true if there are any inconsistent documents
	 * 
	 * @return true if there are any inconsistent documents
	 */
	public boolean hasInconsistentDocuments() {
		return hasInconsistentDocuments;
	}

	public String getConfigurationName() {
		return migrationConfiguration.getConfigurationName();
	}

	public void setConfigurationName(String configurationName) {
		this.migrationConfiguration.setConfigurationName(configurationName);
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public String getHostName() {
		return hostName;
	}

	public void setHostName(String hostName) {
		this.hostName = hostName;
	}

	public String getPid() {
		return pid;
	}

	public void setPid(String pid) {
		this.pid = pid;
	}

	public Date getStartDate() {
		return startDate;
	}

	public void setStartDate(Date startDate) {
		this.startDate = startDate;
	}

	public Date getEndDate() {
		return endDate;
	}

	public void setEndDate(Date endDate) {
		this.endDate = endDate;
	}

	public Date getWhenAvailable() {
		return whenAvailable;
	}

	public void setWhenAvailable(Date whenAvailable) {
		this.whenAvailable = whenAvailable;
	}

	public int getExpectedRunTime() {
		return expectedRunTime;
	}

	public void setExpectedRunTime(int expectedRunTime) {
		this.expectedRunTime = expectedRunTime;
	}
	
	public int getDocumentsProcessed() {
		return currentRun.getDocumentsProcessed();
	}

	public int getConsistentDocuments() {
		return currentRun.getConsistentDocuments();
	}

	public int getInconsistentDocuments() {
		return currentRun.getInconsistentDocuments();
	}

	public int getRecordsOverwritten() {
		return currentRun.getRecordsOverwritten();
	}

	@Override
	public void run() {
		LOGGER.info("MigrationJob started");

		currentRun = new MigrationJobRun();
		currentRun.setOwner(owner);
		currentRun.setHostName(hostName);
		currentRun.setPid(pid);
		currentRun.setActualStartDate(new Date());
		getJobRuns().add(currentRun);
		
		saveJobDetails();

		configureClients();

		Map<String, JsonNode> sourceDocuments = getSourceDocuments();

		Map<String, JsonNode> destinationDocuments = getDestinationDocuments(sourceDocuments);

		List<JsonNode> documentsToOverwrite = getDocumentsToOverwrite(sourceDocuments, destinationDocuments);


		if (!documentsToOverwrite.isEmpty()) {
			hasInconsistentDocuments = true;
		}

		currentRun.setDocumentsProcessed(sourceDocuments.size());
		currentRun.setConsistentDocuments(sourceDocuments.size() - documentsToOverwrite.size());
		currentRun.setInconsistentDocuments(documentsToOverwrite.size());

		if (shouldOverwriteDestinationDocuments()) {
			currentRun.setRecordsOverwritten(overwriteLightblue(documentsToOverwrite));
		}
		
		currentRun.setCompleted(true);
		currentRun.setActualEndDate(new Date());
		
		saveJobDetails();

		LOGGER.info("MigrationJob completed");
	}

	private void configureClients() {
		if (getSourceConfigPath() == null && getDestinationConfigPath() == null) {
			sourceClient = new LightblueHttpClient();
			destinationClient = new LightblueHttpClient();
		} else {
			sourceClient = new LightblueHttpClient(getSourceConfigPath());
			destinationClient = new LightblueHttpClient(getDestinationConfigPath());
		}
	}

	private LightblueResponse saveJobDetails() {
		DataSaveRequest saveRequest = new DataSaveRequest(getJobConfiguration().getDestinationEntityName(), getJobConfiguration().getDestinationEntityVersion());
		saveRequest.setBody(this.toJson());
		return saveDestinationData(saveRequest);
	}

	private int overwriteLightblue(List<JsonNode> documentsToOverwrite) {
		DataSaveRequest saveRequest = new DataSaveRequest(getJobConfiguration().getDestinationEntityName(), getJobConfiguration().getDestinationEntityVersion());
		StringBuilder body = new StringBuilder();
		for (JsonNode document : documentsToOverwrite) {
			body.append(document.toString());
		}
		saveRequest.setBody(body.toString());
		LightblueResponse response = saveDestinationData(saveRequest);
		return response.getJson().findValue("modifiedCount").asInt();
	}

	protected Map<String, JsonNode>  getSourceDocuments() {
		Map<String, JsonNode> sourceDocuments = new LinkedHashMap<>();
		try {
			DataFindRequest sourceRequest = new DataFindRequest(getJobConfiguration().getSourceEntityName(), getJobConfiguration().getSourceEntityVersion());
			List<Query> conditions = new LinkedList<>();
			conditions.add(withValue(getJobConfiguration().getSourceTimestampPath() + " >= " + getStartDate()));
			conditions.add(withValue(getJobConfiguration().getSourceTimestampPath() + " <= " + getEndDate()));
			sourceRequest.where(and(conditions));
			sourceRequest.select(includeFieldRecursively("*"));
			sourceDocuments = findSourceData(sourceRequest);
		} catch (IOException e) {
			LOGGER.error("Problem getting sourceDocuments", e);
		}
		return sourceDocuments;
	}

	protected Map<String, JsonNode> getDestinationDocuments(Map<String, JsonNode> sourceDocuments) {
		Map<String, JsonNode> destinationDocuments = new LinkedHashMap<>();
		try {
			DataFindRequest destinationRequest = new DataFindRequest(getJobConfiguration().getDestinationEntityName(), getJobConfiguration().getDestinationEntityVersion());
			List<Query> requestConditions = new LinkedList<>();
			for(Map.Entry<String, JsonNode> sourceDocument : sourceDocuments.entrySet()) {
				List<Query> docConditions = new LinkedList<>();
				for(String keyField : getJobConfiguration().getDestinationEntityKeyFields()) {
					ValueQuery docQuery = new ValueQuery(keyField + " = " + sourceDocument.getValue().findValue(keyField).asText());
					docConditions.add(docQuery);	
				}	
				requestConditions.add(and(docConditions));
			}
			destinationRequest.where(or(requestConditions));
			destinationRequest.select(includeFieldRecursively("*"));
			destinationRequest.sort(new SortCondition(getJobConfiguration().getSourceTimestampPath(), SortDirection.ASC));
			destinationDocuments = findDestinationData(destinationRequest);
		} catch (IOException e) {
			LOGGER.error("Error getting destinationDocuments", e);
		}
		return destinationDocuments;
	}

	protected List<JsonNode> getDocumentsToOverwrite(Map<String, JsonNode> sourceDocuments, Map<String, JsonNode> destinationDocuments) {
		List<JsonNode> documentsToOverwrite = new ArrayList<>();
		for(Map.Entry<String, JsonNode> sourceDocument : sourceDocuments.entrySet()) {
			JsonNode destinationDocument = destinationDocuments.get(sourceDocument.getKey());
			if(destinationDocument == null) {
				documentsToOverwrite.add(sourceDocument.getValue());
			} else if (!documentsConsistent(sourceDocument.getValue(), destinationDocument)) {
				documentsToOverwrite.add(sourceDocument.getValue());
			}
		}
		return documentsToOverwrite;
	}
	
	protected boolean documentsConsistent(JsonNode sourceDocument, JsonNode destinationDocument) {
		boolean consistent = true;
		
		Iterator<Entry<String, JsonNode>> nodeIterator = sourceDocument.fields();
		
		while (nodeIterator.hasNext()) {
			Entry<String, JsonNode> sourceNode = nodeIterator.next();
			
			if(!getJobConfiguration().getComparisonExclusionPaths().contains(sourceNode.getKey())) {
				if(!sourceNode.getValue().equals(destinationDocument.findValue(sourceNode.getKey()))) {
					consistent = false;
					break;
				}	
			}
		}
		return consistent;
	}
	
	protected Map<String, JsonNode> findSourceData(LightblueRequest findRequest) throws IOException {
		return getJsonNodeMap(getSourceClient().data(findRequest, JsonNode[].class), getJobConfiguration().getDestinationEntityKeyFields());
	}
	
	protected Map<String, JsonNode> findDestinationData(LightblueRequest findRequest) throws IOException {
		return getJsonNodeMap(getDestinationClient().data(findRequest, JsonNode[].class), getJobConfiguration().getDestinationEntityKeyFields());
	}

	protected LinkedHashMap<String, JsonNode> getJsonNodeMap(JsonNode[] results, List<String> entityKeyFields) {
		LinkedHashMap<String, JsonNode> resultsMap = new LinkedHashMap<>();
		for(JsonNode result : results) {
			StringBuilder resultKey = new StringBuilder();
			for(String keyField : entityKeyFields) {
				resultKey.append(result.findValue(keyField)).append("|||");
			}
			resultsMap.put(resultKey.toString(), result);
		}
		return resultsMap;
	}
	
	protected LightblueResponse saveDestinationData(LightblueRequest saveRequest) {
		return getDestinationClient().data(saveRequest);
	}

	private String toJson() {
        StringBuilder json = new StringBuilder();
		ObjectMapper mapper = new ObjectMapper();
		try {
			json.append(mapper.writeValueAsString(MigrationJob.class));
		} catch (JsonProcessingException e) {
			LOGGER.error("Error transforming to JSON", e);
		}
		return json.toString();
	}

}
