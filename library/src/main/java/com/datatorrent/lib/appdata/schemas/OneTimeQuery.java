/*
 *  Copyright (c) 2012-2015 Malhar, Inc.
 *  All Rights Reserved.
 */

package com.datatorrent.lib.appdata.schemas;

import com.datatorrent.lib.appdata.qr.Query;
import com.datatorrent.lib.appdata.qr.QueryDeserializerInfo;
import com.datatorrent.lib.appdata.qr.SchemaInfo;
import com.datatorrent.lib.appdata.qr.SimpleQueryDeserializer;

/**
 *
 * @author Timothy Farkas: tim@datatorrent.com
 */
@SchemaInfo(type=OneTimeQuery.TYPE)
@QueryDeserializerInfo(clazz=SimpleQueryDeserializer.class)
public class OneTimeQuery extends Query
{
  public static final String TYPE = "oneTimeQuery";

  private OneTimeQueryData data;

  public OneTimeQuery()
  {
  }

  /**
   * @return the data
   */
  public OneTimeQueryData getData()
  {
    return data;
  }

  /**
   * @param data the data to set
   */
  public void setData(OneTimeQueryData data)
  {
    this.data = data;
  }

  public static class OneTimeQueryData
  {
    private TimeRangeBuckets time;

    /**
     * @return the time
     */
    public TimeRangeBuckets getTime()
    {
      return time;
    }

    /**
     * @param time the time to set
     */
    public void setTime(TimeRangeBuckets time)
    {
      this.time = time;
    }
  }
}
