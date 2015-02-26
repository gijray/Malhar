/*
 * Copyright (c) 2014 DataTorrent, Inc. ALL Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.demos.dimensions.ads;

import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.DefaultInputPort;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.api.annotation.AppDataQueryPort;
import com.datatorrent.api.annotation.AppDataResultPort;
import com.datatorrent.api.annotation.InputPortFieldAnnotation;
import com.datatorrent.common.util.Slice;
import com.datatorrent.contrib.hdht.AbstractSinglePortHDHTWriter;
import com.datatorrent.demos.dimensions.ads.AdInfo.AdInfoAggregateEvent;
import com.datatorrent.demos.dimensions.ads.AdInfo.AdInfoAggregator;
import com.datatorrent.lib.appdata.qr.Query;
import com.datatorrent.lib.appdata.qr.QueryDeserializerFactory;
import com.datatorrent.lib.appdata.qr.Result;
import com.datatorrent.lib.appdata.qr.ResultSerializerFactory;
import com.datatorrent.lib.appdata.qr.processor.QueryComputer;
import com.datatorrent.lib.appdata.qr.processor.QueryProcessor;
import com.datatorrent.lib.appdata.qr.processor.WWEQueryQueueManager;
import com.datatorrent.lib.appdata.schemas.SchemaQuery;
import com.datatorrent.lib.appdata.schemas.ads.AdsKeys;
import com.datatorrent.lib.appdata.schemas.ads.AdsOneTimeQuery;
import com.datatorrent.lib.appdata.schemas.ads.AdsOneTimeResult;
import com.datatorrent.lib.appdata.schemas.ads.AdsSchemaResult;
import com.datatorrent.lib.appdata.schemas.ads.AdsTimeRangeBucket;
import com.datatorrent.lib.appdata.schemas.ads.AdsUpdateQuery;
import com.datatorrent.lib.appdata.schemas.ads.AdsUpdateResult;
import com.datatorrent.lib.codec.KryoSerializableStreamCodec;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableLong;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AdsDimension Store Operator
 *
 * @displayName Dimensional Store
 * @category Store
 * @tags storage, hdfs, dimensions, hdht
 *
 * @since 2.0.0
 */
public class AdsDimensionStoreOperator extends AbstractSinglePortHDHTWriter<AdInfoAggregateEvent>
{
  private static final Logger LOG = LoggerFactory.getLogger(AdsDimensionStoreOperator.class);

  @AppDataResultPort(schemaType = "default", schemaVersion = "1.0")
  public final transient DefaultOutputPort<String> queryResult = new DefaultOutputPort<String>();

  @InputPortFieldAnnotation(optional = true)
  @AppDataQueryPort
  public transient final DefaultInputPort<String> query = new DefaultInputPort<String>()
  {
    @Override public void process(String s)
    {
      LOG.info("Received: {}", s);

      Query query = queryDeserializerFactory.deserialize(s);

      //Query was not parseable
      if(query == null) {
        LOG.info("Not parseable.");
        return;
      }

      if(query instanceof SchemaQuery) {
        LOG.info("Received schemaquery.");
        String schemaResult = resultSerializerFactory.serialize(new AdsSchemaResult(query));
        queryResult.emit(schemaResult);
      }
      else if(query instanceof AdsUpdateQuery) {
        LOG.info("Received AdsUpdateQuery");
        queryProcessor.enqueue(query, null, null);
      }
      else if(query instanceof AdsOneTimeQuery) {
        LOG.info("Received AdsOneTimeQuery");
        queryProcessor.enqueue(query, null, null);
      }
    }
  };

  private transient final ByteBuffer valbb = ByteBuffer.allocate(8 * 4);
  private transient final ByteBuffer keybb = ByteBuffer.allocate(8 + 4 * 4);
  protected boolean debug = false;

  protected static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS");
  // in-memory aggregation before hitting WAL
  protected final SortedMap<Long, Map<AdInfoAggregateEvent, AdInfoAggregateEvent>> minuteCache = Maps.newTreeMap();
  protected final SortedMap<Long, Map<AdInfoAggregateEvent, AdInfoAggregateEvent>> hourCache = Maps.newTreeMap();
  protected final SortedMap<Long, Map<AdInfoAggregateEvent, AdInfoAggregateEvent>> dayCache = Maps.newTreeMap();
  // TODO: should be aggregation interval count
  private int maxCacheSize = 20;

  private AdInfoAggregator aggregator;

  private transient ObjectMapper mapper = null;
  //The default number of buckets to output for an updateQuery.
  private long defaultTimeWindow = 20;

  //==========================================================================
  // Query Processing - Start
  //==========================================================================

  private transient QueryProcessor<Query, AdsQueryMeta, MutableLong, MutableBoolean> queryProcessor;
  @SuppressWarnings("unchecked")
  private transient QueryDeserializerFactory queryDeserializerFactory;
  private transient ResultSerializerFactory resultSerializerFactory;
  private static final Long QUERY_QUEUE_WINDOW_COUNT = 30L;
  private static final int QUERY_QUEUE_WINDOW_COUNT_INT = (int) ((long) QUERY_QUEUE_WINDOW_COUNT);

  private transient long windowId;

  //==========================================================================
  // Query Processing - End
  //==========================================================================

  public int getMaxCacheSize()
  {
    return maxCacheSize;
  }

  public void setMaxCacheSize(int maxCacheSize)
  {
    this.maxCacheSize = maxCacheSize;
  }

  public long getDefaultTimeWindow()
  {
    return defaultTimeWindow;
  }

  public void setDefaultTimeWindow(long defaultTimeWindow)
  {
    this.defaultTimeWindow = defaultTimeWindow;
  }

  public AdInfoAggregator getAggregator()
  {
    return aggregator;
  }

  public void setAggregator(AdInfoAggregator aggregator)
  {
    this.aggregator = aggregator;
  }

  public boolean isDebug()
  {
    return debug;
  }

  public void setDebug(boolean debug)
  {
    this.debug = debug;
  }

  /**
   * Perform aggregation in memory until HDHT flush threshold is reached.
   * Avoids WAL writes for most of the aggregation.
   */
  @Override
  protected void processEvent(AdInfoAggregateEvent event) throws IOException
  {
    AdInfoAggregateEvent minuteEvent = new AdInfoAggregateEvent(event,
                                                                AdInfoAggregateEvent.MINUTE_BUCKET);
    AdInfoAggregateEvent hourEvent = new AdInfoAggregateEvent(event,
                                                              AdInfoAggregateEvent.HOUR_BUCKET);
    AdInfoAggregateEvent dayEvent = new AdInfoAggregateEvent(event,
                                                             AdInfoAggregateEvent.DAY_BUCKET);
    processToBucket(minuteEvent, minuteCache);
    processToBucket(hourEvent, hourCache);
    processToBucket(dayEvent, dayCache);
  }

  private void processToBucket(AdInfoAggregateEvent event,
                               SortedMap<Long, Map<AdInfoAggregateEvent, AdInfoAggregateEvent>> cache)
  {
    /*
    if(event.advertiserId == 1 &&
       event.publisherId == 4 &&
       event.adUnit == 3) {
      LOG.info("desired tuple {} ",
               AdsTimeRangeBucket.sdf.format(new Date(event.getTimestamp())));
    }
    */

    Map<AdInfoAggregateEvent, AdInfoAggregateEvent> valMap = cache.get(event.getTimestamp());

    if (valMap == null) {
      valMap = new HashMap<AdInfoAggregateEvent, AdInfoAggregateEvent>();
      valMap.put(event, event);
      cache.put(event.getTimestamp(), valMap);
    } else {
      AdInfoAggregateEvent val = valMap.get(event);
      // FIXME: lookup from store as the value may have been flushed previously.
      if (val == null) {
        valMap.put(event, event);
        return;
      } else {
        aggregator.aggregate(val, event);
      }
    }
  }

  @Override
  protected HDHTCodec<AdInfoAggregateEvent> getCodec()
  {
    return new AdInfoAggregateCodec();
  }

  @Override
  public void setup(OperatorContext context)
  {
    super.setup(context);

    //Setup for query processing
    queryProcessor =
    new QueryProcessor<Query, AdsQueryMeta, MutableLong, MutableBoolean>(
                                                  new AdsQueryComputer<Query>(this),
                                                  new AdsQueryQueueManager<Query>(this, QUERY_QUEUE_WINDOW_COUNT_INT));
    queryDeserializerFactory = new QueryDeserializerFactory(SchemaQuery.class,
                                                            AdsUpdateQuery.class,
                                                            AdsOneTimeQuery.class);
    resultSerializerFactory = new ResultSerializerFactory();

    queryProcessor.setup(context);
  }

  @Override
  public void beginWindow(long windowId)
  {
    this.windowId = windowId;
    queryProcessor.beginWindow(windowId);
    super.beginWindow(windowId);
  }

  @Override
  public void endWindow()
  {
    flushCache(minuteCache);
    flushCache(hourCache);
    flushCache(dayCache);

    /*for(Long timestamp: hourCache.keySet()) {
      String startString = AdsTimeRangeBucket.sdf.format(new Date(timestamp));

      LOG.info("Hour cache key {}", startString);

    }*/

    MutableBoolean done = new MutableBoolean(false);

    super.endWindow();

    while(done.isFalse()) {
      Result aotr = queryProcessor.process(done);

      if(done.isFalse()) {
        LOG.debug("Query: {}", this.windowId);
      }

      if(aotr != null) {
        String result = resultSerializerFactory.serialize(aotr);
        LOG.info("Emitting the result: {}", result);
        queryResult.emit(result);
      }
    }

    queryProcessor.endWindow();
  }

  private void flushCache(SortedMap<Long, Map<AdInfoAggregateEvent, AdInfoAggregateEvent>> cache)
  {
    // flush final aggregates
    int expiredEntries = cache.size() - maxCacheSize;
    while(expiredEntries-- > 0){

      Map<AdInfoAggregateEvent, AdInfoAggregateEvent> vals = cache.remove(cache.firstKey());
      for (Entry<AdInfoAggregateEvent, AdInfoAggregateEvent> en : vals.entrySet()) {
        AdInfoAggregateEvent ai = en.getValue();
        try {
          put(getBucketKey(ai), new Slice(getKey(ai)), getValue(ai));
        } catch (IOException e) {
          LOG.warn("Error putting the value", e);
        }
      }
    }
  }

  @Override
  public void teardown()
  {
    queryProcessor.teardown();
    super.teardown();
  }

  protected byte[] getKey(AdInfo event)
  {
    if (debug) {
      StringBuilder keyBuilder = new StringBuilder(32);
      keyBuilder.append(sdf.format(new Date(event.timestamp)));
      keyBuilder.append("|publisherId:").append(event.publisherId);
      keyBuilder.append("|advertiserId:").append(event.advertiserId);
      keyBuilder.append("|adUnit:").append(event.adUnit);
      return keyBuilder.toString().getBytes();
    }

    byte[] data = new byte[8 + 4 * 4];
    keybb.rewind();
    keybb.putLong(event.getTimestamp());
    keybb.putInt(event.getPublisherId());
    keybb.putInt(event.getAdvertiserId());
    keybb.putInt(event.getAdUnit());
    keybb.putInt(event.getBucket());
    keybb.rewind();
    keybb.get(data);
    //LOG.debug("Value: {}", event);
    //LOG.debug("Key: {}", DatatypeConverter.printHexBinary(data));
    return data;
  }

  protected byte[] getValue(AdInfoAggregateEvent event)
  {
    if (debug) {
      StringBuilder keyBuilder = new StringBuilder(32);
      keyBuilder.append("|clicks:").append(event.clicks);
      keyBuilder.append("|cost:").append(event.cost);
      keyBuilder.append("|impressions:").append(event.impressions);
      keyBuilder.append("|revenue:").append(event.revenue);
      return keyBuilder.toString().getBytes();
    }
    byte[] data = new byte[8 * 4];
    valbb.rewind();
    valbb.putLong(event.clicks);
    valbb.putDouble(event.cost);
    valbb.putLong(event.impressions);
    valbb.putDouble(event.revenue);
    valbb.rewind();
    valbb.get(data);
    //LOG.debug("Key: {}", DatatypeConverter.printHexBinary(data));
    return data;
  }

  private AdInfo.AdInfoAggregateEvent getAggregateFromString(String key, String value)
  {
    AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();
    Pattern p = Pattern.compile("([^|]*)\\|publisherId:(\\d+)\\|advertiserId:(\\d+)\\|adUnit:(\\d+)");
    Matcher m = p.matcher(key);
    m.find();
    try {
      Date date = sdf.parse(m.group(1));
      ae.timestamp = date.getTime();
    } catch (Exception ex) {
      ae.timestamp = 0;
    }
    ae.publisherId = Integer.valueOf(m.group(2));
    ae.advertiserId = Integer.valueOf(m.group(3));
    ae.adUnit = Integer.valueOf(m.group(4));

    p = Pattern.compile("\\|clicks:(.*)\\|cost:(.*)\\|impressions:(.*)\\|revenue:(.*)");
    m = p.matcher(value);
    m.find();
    ae.clicks = Long.valueOf(m.group(1));
    ae.cost = Double.valueOf(m.group(2));
    ae.impressions = Long.valueOf(m.group(3));
    ae.revenue = Double.valueOf(m.group(4));
    return ae;
  }

  protected AdInfo.AdInfoAggregateEvent bytesToAggregate(Slice key, byte[] value)
  {
    if (key == null || value == null)
      return null;

    AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();
    if (debug) {
      return getAggregateFromString(new String(key.buffer, key.offset, key.length), new String(value));
    }

    java.nio.ByteBuffer bb = ByteBuffer.wrap(key.buffer, key.offset, key.length);
    ae.timestamp = bb.getLong();
    ae.publisherId = bb.getInt();
    ae.advertiserId = bb.getInt();
    ae.adUnit = bb.getInt();
    ae.bucket = bb.getInt();

    bb = ByteBuffer.wrap(value);
    ae.clicks = bb.getLong();
    ae.cost = bb.getDouble();
    ae.impressions = bb.getLong();
    ae.revenue = bb.getDouble();
    return ae;
  }

  public static class AdInfoAggregateCodec extends KryoSerializableStreamCodec<AdInfoAggregateEvent> implements HDHTCodec<AdInfoAggregateEvent>
  {
    public AdsDimensionStoreOperator operator;

    @Override
    public byte[] getKeyBytes(AdInfoAggregateEvent aggr)
    {
      byte[] array = operator.getKey(aggr);
      //LOG.debug("Key: {}", DatatypeConverter.printHexBinary(array));
      return array;
    }

    @Override
    public byte[] getValueBytes(AdInfoAggregateEvent aggr)
    {
      byte[] array = operator.getValue(aggr);
      //LOG.debug("Value: {}", DatatypeConverter.printHexBinary(array));
      return array;
    }

    @Override
    public AdInfoAggregateEvent fromKeyValue(Slice key, byte[] value)
    {
      if (key == null || value == null)
        return null;

      AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();
      if (operator.debug) {
        return operator.getAggregateFromString(new String(key.buffer, key.offset, key.length), new String(value));
      }

      java.nio.ByteBuffer bb = ByteBuffer.wrap(key.buffer, key.offset, key.length);
      ae.timestamp = bb.getLong();
      ae.publisherId = bb.getInt();
      ae.advertiserId = bb.getInt();
      ae.adUnit = bb.getInt();
      ae.bucket = bb.getInt();

      bb = ByteBuffer.wrap(value);
      ae.clicks = bb.getLong();
      ae.cost = bb.getDouble();
      ae.impressions = bb.getLong();
      ae.revenue = bb.getDouble();
      return ae;
    }

    @Override
    public int getPartition(AdInfoAggregateEvent t)
    {
      final int prime = 31;
      int result = 1;
      result = prime * result + t.adUnit;
      result = prime * result + t.advertiserId;
      result = prime * result + t.publisherId;
      return result;
    }
        private static final long serialVersionUID = 201411031407L;

  }

  //==========================================================================
  // Query Processing Classes - Start
  //==========================================================================

  class AdsQueryQueueManager<QT extends Query> extends WWEQueryQueueManager<QT, AdsQueryMeta>
  {
    private AdsDimensionStoreOperator operator;
    private int queueWindowCount;

    public AdsQueryQueueManager(AdsDimensionStoreOperator operator,
                                  int queueWindowCount)
    {
      this.operator = operator;
      this.queueWindowCount = queueWindowCount;
    }

    @Override
    public boolean enqueue(QT query, AdsQueryMeta queryMeta, MutableLong windowExpireCount)
    {
      LOG.info("Enqueueing query {}", query);
      AdInfo.AdInfoAggregateEvent ae = new AdInfo.AdInfoAggregateEvent();

      AdsKeys aks;
      long endTime = -1L;
      long startTime = -1L;
      TimeUnit bucketUnit = null;
      int bucket = 0;

      if(query instanceof AdsOneTimeQuery) {
        AdsOneTimeQuery aotq = (AdsOneTimeQuery) query;
        aks = aotq.getData().getKeys();

        AdsTimeRangeBucket atrb = aotq.getData().getTime();

        bucket = AdInfo.BUCKET_NAME_TO_INDEX.get(atrb.getBucket());
        bucketUnit = AdInfo.BUCKET_TO_TIMEUNIT.get(bucket);

        startTime = atrb.getFromLong();
        endTime = atrb.getToLong();

        if(bucket == AdInfo.MINUTE_BUCKET) {
          LOG.info("Minute bucket");
          startTime = AdInfo.roundMinute(startTime);
          endTime = AdInfo.roundMinuteUp(endTime);
        }
        else if(bucket == AdInfo.HOUR_BUCKET) {
          LOG.info("Hour bucket");
          startTime = AdInfo.roundHour(startTime);
          endTime = AdInfo.roundHourUp(endTime);
        }
        else if(bucket == AdInfo.DAY_BUCKET) {
          LOG.info("Day bucket");
          startTime = AdInfo.roundDay(startTime);
          endTime = AdInfo.roundDayUp(endTime);
        }
      }
      else if(query instanceof AdsUpdateQuery) {
        AdsUpdateQuery tauq = (AdsUpdateQuery) query;
        aks = tauq.getData().getKeys();
        bucket = AdInfo.BUCKET_NAME_TO_INDEX.get(tauq.getData().getTime().getBucket());
        bucketUnit = AdInfo.BUCKET_TO_TIMEUNIT.get(bucket);

        long time = System.currentTimeMillis();

        if(bucket == AdInfo.MINUTE_BUCKET) {
          endTime = AdInfo.roundMinute(time);
        }
        else if(bucket == AdInfo.HOUR_BUCKET) {
          endTime = AdInfo.roundHour(time);
        }
        else if(bucket == AdInfo.DAY_BUCKET) {
          endTime = AdInfo.roundDay(time);
        }

        startTime = endTime - bucketUnit.toMillis(defaultTimeWindow);
      }
      else {
        throw new UnsupportedOperationException("Processing query of type " +
                                                query.getClass() +
                                                " is not supported.");
      }

      String startString = AdsTimeRangeBucket.sdf.format(new Date(startTime));
      String endString = AdsTimeRangeBucket.sdf.format(new Date(endTime));

      LOG.info("start {}, end {}", startString, endString);

      ae.setTimestamp(startTime);
      ae.adUnit = aks.getLocationId();
      ae.publisherId = aks.getPublisherId();
      ae.advertiserId = aks.getAdvertiserId();
      ae.bucket = bucket;

      LOG.debug("Input AdEvent: {}", ae);

      long bucketKey = getBucketKey(ae);
      if(!(operator.partitions == null || operator.partitions.contains((int)bucketKey))) {
        LOG.debug("Ignoring query for bucket {} when this partition serves {}", bucketKey, operator.partitions);
        return false;
      }

      List<HDSQuery> hdsQueries = Lists.newArrayList();

      for(ae.timestamp = startTime;
          ae.timestamp <= endTime;
          ae.timestamp += bucketUnit.toMillis(1)) {
        LOG.debug("Query AdEvent: {}", ae);
        Slice key = new Slice(getKey(ae));
        HDSQuery hdsQuery = operator.queries.get(key);

        if(hdsQuery == null) {
          hdsQuery = new HDSQuery();
          hdsQuery.bucketKey = bucketKey;
          hdsQuery.key = key;
          operator.addQuery(hdsQuery);
        }
        else {
          if(hdsQuery.result == null) {
            LOG.debug("Forcing refresh for {}", hdsQuery);
            hdsQuery.processed = false;
          }
        }

        hdsQuery.keepAliveCount = queueWindowCount;
        hdsQueries.add(hdsQuery);
      }

      AdsQueryMeta aqm = new AdsQueryMeta();
      aqm.setBeginTime(startTime);
      aqm.setAdInofAggregateEvent(ae);
      aqm.setHdsQueries(hdsQueries);

      return super.enqueue(query, aqm, new MutableLong(queueWindowCount));
    }
  }

  class AdsQueryComputer<QT extends Query> implements QueryComputer<QT, AdsQueryMeta, MutableLong, MutableBoolean>
  {
    private AdsDimensionStoreOperator operator;

    public AdsQueryComputer(AdsDimensionStoreOperator operator)
    {
      this.operator = operator;
    }

    @Override
    public Result processQuery(QT query, AdsQueryMeta adsQueryMeta, MutableLong queueContext, MutableBoolean context)
    {
      LOG.info("Processing query {}", query);
      AdsOneTimeResult result = null;

      Set<String> fieldSet = null;

      if(query instanceof AdsOneTimeQuery) {
        List<String> fields = ((AdsOneTimeQuery) query).getData().getFields();

        if(fields != null) {
          fieldSet = Sets.newHashSet(fields);
        }
        else {
          fieldSet = Sets.newHashSet();
        }

        AdsOneTimeResult aotr = new AdsOneTimeResult(query);
        result = aotr;
        aotr.setData(new ArrayList<AdsOneTimeResult.AdsOneTimeData>());
      }
      else if(query instanceof AdsUpdateQuery) {
        List<String> fields = ((AdsUpdateQuery) query).getData().getFields();

        if(fields != null) {
          fieldSet = Sets.newHashSet(fields);
        }
        else {
          fieldSet = Sets.newHashSet();
        }

        AdsUpdateResult aur = new AdsUpdateResult(query);
        aur.setCountdown(queueContext.longValue());
        result = aur;
        aur.setData(new ArrayList<AdsOneTimeResult.AdsOneTimeData>());
      }
      else {
        throw new UnsupportedOperationException("The given query type " +
                                                query.getClass() + " is not supported.");
      }

      AdInfo.AdInfoAggregateEvent prototype = adsQueryMeta.getAdInofAggregateEvent();
      TimeUnit bucketUnit = AdInfo.BUCKET_TO_TIMEUNIT.get(prototype.bucket);
      Iterator<HDSQuery> queryIt = adsQueryMeta.getHdsQueries().iterator();

      SortedMap<Long, Map<AdInfoAggregateEvent, AdInfoAggregateEvent>> cache = null;
      boolean hour = false;

      if(prototype.bucket == AdInfo.MINUTE_BUCKET) {
        LOG.info("Minute bucket");
        cache = minuteCache;
      }
      else if(prototype.bucket == AdInfo.HOUR_BUCKET) {
        hour = true;
        LOG.info("Hour bucket");
        cache = hourCache;
      }
      else if(prototype.bucket == AdInfo.DAY_BUCKET) {
        LOG.info("Day bucket");
        cache = dayCache;
      }

      boolean allSatisfied = true;

      for(long timestamp = adsQueryMeta.getBeginTime();
          queryIt.hasNext();
          timestamp += bucketUnit.toMillis(1))
      {
        HDSQuery hdsQuery = queryIt.next();
        prototype.setTimestamp(timestamp);

        Map<AdInfoAggregateEvent, AdInfoAggregateEvent> buffered = cache.get(timestamp);

        // TODO
        // There is a race condition with retrieving from the cache and doing
        // an hds query. If an hds query finishes for a key while it is in the minuteCache, but
        // then that key gets evicted from the minuteCache, then the value will never be retrieved.
        // A list of evicted keys should be kept, so that corresponding queries can be refreshed.
        if(buffered != null) {
          LOG.info("query prototype: {}", prototype);
          AdInfo.AdInfoAggregateEvent ae = buffered.get(prototype);

          if(ae != null) {
            LOG.info("Adding from aggregation buffer {}" + ae);
            AdsOneTimeResult.AdsOneTimeData aotd = convert(fieldSet, ae);
            result.getData().add(aotd);
          }
        }
        else if(hdsQuery.processed) {
          if(hdsQuery.result != null) {
            AdInfo.AdInfoAggregateEvent ae = operator.codec.fromKeyValue(hdsQuery.key, hdsQuery.result);
            AdsOneTimeResult.AdsOneTimeData aotd = convert(fieldSet, ae);

            if(ae != null) {
              LOG.debug("Adding from hds");
              result.getData().add(aotd);
            }
          }
        }
        else {
          allSatisfied = false;
        }
      }

      if(result.getData().isEmpty()) {
        return null;
      }

      if(query instanceof AdsOneTimeQuery) {
        if(!allSatisfied && queueContext.longValue() > 1L) {
          return null;
        }
        else {
          //Expire query.
          queueContext.setValue(0L);
        }
      }

      return result;
    }

    @Override
    public void queueDepleted(MutableBoolean context)
    {
      context.setValue(true);
    }

    private AdsOneTimeResult.AdsOneTimeData convert(Set<String> fieldSet,
                                                    AdInfo.AdInfoAggregateEvent ae)
    {
      AdsOneTimeResult.AdsOneTimeData aotd = new AdsOneTimeResult.AdsOneTimeData();

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.TIME)) {
        aotd.setTimeLong(ae.timestamp);
      }

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.ADVERTISER)) {
        aotd.setAdvertiserId(ae.advertiserId);
      }

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.PUBLISHER)) {
        aotd.setPublisherId(ae.publisherId);
      }

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.LOCATION)) {
        aotd.setLocationId(ae.adUnit);
      }

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.IMPRESSIONS)) {
        aotd.setImpressions(ae.impressions);
      }

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.CLICKS)) {
        aotd.setClicks(ae.clicks);
      }

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.COST)) {
        aotd.setCost(ae.cost);
      }

      if(fieldSet.isEmpty() || fieldSet.contains(AdsSchemaResult.REVENUE)) {
        aotd.setRevenue(ae.revenue);
      }

      return aotd;
    }
  }

  static class AdsQueryMeta
  {
    private long beginTime;
    private List<HDSQuery> hdsQueries;
    private AdInfo.AdInfoAggregateEvent adInofAggregateEvent;

    public AdsQueryMeta()
    {
    }

    /**
     * @return the hdsQueries
     */
    public List<HDSQuery> getHdsQueries()
    {
      return hdsQueries;
    }

    /**
     * @param hdsQueries the hdsQueries to set
     */
    public void setHdsQueries(List<HDSQuery> hdsQueries)
    {
      this.hdsQueries = hdsQueries;
    }

    /**
     * @return the adInofAggregateEvent
     */
    public AdInfo.AdInfoAggregateEvent getAdInofAggregateEvent()
    {
      return adInofAggregateEvent;
    }

    /**
     * @param adInofAggregateEvent the adInofAggregateEvent to set
     */
    public void setAdInofAggregateEvent(AdInfo.AdInfoAggregateEvent adInofAggregateEvent)
    {
      this.adInofAggregateEvent = adInofAggregateEvent;
    }

    /**
     * @return the beginTime
     */
    public long getBeginTime()
    {
      return beginTime;
    }

    /**
     * @param beginTime the beginTime to set
     */
    public void setBeginTime(long beginTime)
    {
      this.beginTime = beginTime;
    }
  }

  //==========================================================================
  // Query Processing Classes - End
  //==========================================================================
}
