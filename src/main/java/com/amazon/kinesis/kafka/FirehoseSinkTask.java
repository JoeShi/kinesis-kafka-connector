package com.amazon.kinesis.kafka;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.ClientConfigurationFactory;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseClient;
import com.amazonaws.services.kinesisfirehose.model.AmazonKinesisFirehoseException;
import com.amazonaws.services.kinesisfirehose.model.DescribeDeliveryStreamRequest;
import com.amazonaws.services.kinesisfirehose.model.DescribeDeliveryStreamResult;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchResult;
import com.amazonaws.services.kinesisfirehose.model.PutRecordRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordResult;
import com.amazonaws.services.kinesisfirehose.model.Record;

/**
 * @author nehalmeh
 *
 */
public class FirehoseSinkTask extends SinkTask {

	private String deliveryStreamName;

	private AmazonKinesisFirehoseClient firehoseClient;

	private boolean batch;

	private int batchSize;

	private int batchSizeInBytes;

	@Override
	public String version() {
		return new FirehoseSinkConnector().version();
	}

	@Override
	public void flush(Map<TopicPartition, OffsetAndMetadata> arg0) {
	}

	@Override
	public void put(Collection<SinkRecord> sinkRecords) {

		if (batch)
			putRecordsInBatch(sinkRecords);
		else
			putRecords(sinkRecords);

	}

	@Override
	public void start(Map<String, String> props) {

		batch = Boolean.parseBoolean(props.get(FirehoseSinkConnector.BATCH));

		batchSize = Integer.parseInt(props.get(FirehoseSinkConnector.BATCH_SIZE));

		batchSizeInBytes = Integer.parseInt(props.get(FirehoseSinkConnector.BATCH_SIZE_IN_BYTES));

		deliveryStreamName = props.get(FirehoseSinkConnector.DELIVERY_STREAM);

		firehoseClient = new AmazonKinesisFirehoseClient(new DefaultAWSCredentialsProviderChain());

		firehoseClient.setRegion(RegionUtils.getRegion(props.get(FirehoseSinkConnector.REGION)));

		if (props.get(FirehoseSinkConnector.REGION).startsWith("cn-"))
			firehoseClient.setEndpoint("firehose." + props.get(FirehoseSinkConnector.REGION) + ".amazonaws.com.cn");

		// Validate delivery stream
		validateDeliveryStream();
	}

	@Override
	public void stop() {

	}


	/**
	 * Validates status of given Amazon Kinesis Firehose Delivery Stream.
	 */
	private void validateDeliveryStream() {
		DescribeDeliveryStreamRequest describeDeliveryStreamRequest = new DescribeDeliveryStreamRequest();

		describeDeliveryStreamRequest.setDeliveryStreamName(deliveryStreamName);

		DescribeDeliveryStreamResult describeDeliveryStreamResult = firehoseClient
				.describeDeliveryStream(describeDeliveryStreamRequest);

		if (!describeDeliveryStreamResult.getDeliveryStreamDescription().getDeliveryStreamStatus().equals("ACTIVE"))
			throw new ConfigException("Connecter cannot start as configured delivery stream is not active"
					+ describeDeliveryStreamResult.getDeliveryStreamDescription().getDeliveryStreamStatus());

	}

	/**
	 * Method to perform PutRecordBatch operation with the given record list.
	 *
	 * @param recordList
	 *            the collection of records
	 * @return the output of PutRecordBatch
	 */
	private PutRecordBatchResult putRecordBatch(List<Record> recordList) {
		PutRecordBatchRequest putRecordBatchRequest = new PutRecordBatchRequest();
		putRecordBatchRequest.setDeliveryStreamName(deliveryStreamName);
		putRecordBatchRequest.setRecords(recordList);

		// Put Record Batch records. Max No.Of Records we can put in a
		// single put record batch request is 500 and total size < 4MB
		PutRecordBatchResult putRecordBatchResult = null;
		try {
			putRecordBatchResult = firehoseClient.putRecordBatch(putRecordBatchRequest);
		}catch(AmazonKinesisFirehoseException akfe){
			System.out.println("Amazon Kinesis Firehose Exception:" + akfe.getLocalizedMessage());
		}catch(Exception e){
			System.out.println("Connector Exception" + e.getLocalizedMessage());
		}
		return putRecordBatchResult;
	}

	/**
	 * @param sinkRecords
	 */
	private void putRecordsInBatch(Collection<SinkRecord> sinkRecords) {
		List<Record> recordList = new ArrayList<Record>();
		int recordsInBatch = 0;
		int recordsSizeInBytes = 0;

		for (SinkRecord sinkRecord : sinkRecords) {
			Record record = DataUtility.createRecord(sinkRecord);
			recordList.add(record);
			recordsInBatch++;
			recordsSizeInBytes += record.getData().capacity();

			if (recordsInBatch == batchSize || recordsSizeInBytes > batchSizeInBytes) {
				putRecordBatch(recordList);
				recordList.clear();
				recordsInBatch = 0;
				recordsSizeInBytes = 0;
			}
		}

		if (recordsInBatch > 0) {
			putRecordBatch(recordList);
		}
	}


	/**
	 * @param sinkRecords
	 */
	private void putRecords(Collection<SinkRecord> sinkRecords) {
		for (SinkRecord sinkRecord : sinkRecords) {

			PutRecordRequest putRecordRequest = new PutRecordRequest();
			putRecordRequest.setDeliveryStreamName(deliveryStreamName);
			putRecordRequest.setRecord(DataUtility.createRecord(sinkRecord));

			PutRecordResult putRecordResult;
			try {
				firehoseClient.putRecord(putRecordRequest);
			}catch(AmazonKinesisFirehoseException akfe){
				System.out.println("Amazon Kinesis Firehose Exception:" + akfe.getLocalizedMessage());
			}catch(Exception e){
				System.out.println("Connector Exception" + e.getLocalizedMessage());
			}
		}
	}
}
