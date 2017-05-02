/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

import sage.util.MutableFloat;
import sage.util.MutableInteger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * New Trends need the following:
 * 1. Add to trend list in Carny
 */
public class Agent extends DBObject implements Favorite
{
  private static final int MIN_TOTAL = 2;
  private static final float MIN_WATCH_PROB = 0f;
  private static final float MAX_WATCH_PROB = 0.99f;
  // Enables checking all airings against the optimized airings. This is for testing only.
  static final boolean VERIFY_AIRING_OPTIMIZATION = false;

  private static final int CPU_CONTROL_MOD_COUNT = Sage.getInt("profiler_cpu_mod_count", Sage.EMBEDDED ? 2500 : 25000);

  public static final int LOVE_MASK = 0x0001;
  public static final int TITLE_MASK = 0x0002;
  public static final int CATEGORY_MASK = 0x0004;
  public static final int ACTOR_MASK = 0x0008;
  public static final int NETWORK_MASK = 0x0010;
  public static final int CHANNEL_MASK = 0x0020;
  public static final int RATED_MASK = 0x0040;
  public static final int YEAR_MASK = 0x0080;
  public static final int PR_MASK = 0x0100;
  public static final int DAYSLOT_MASK = 0x0200;
  public static final int TIMESLOT_MASK = 0x0400;
  public static final int FULLSLOT_MASK = 0x0800;
  private static final int PRIME_MASK = 0x1000;
  // These are restrictive, i.e. we'll record reruns unless firstrun_mask is set
  public static final int FIRSTRUN_MASK = 0x2000;
  public static final int RERUN_MASK = 0x4000;
  public static final int KEYWORD_MASK = 0x8000;

  public static final int DONT_AUTODELETE_FLAG = 0x01;
  public static final int KEEP_AT_MOST_MASK = 0x7E; // 6 bits
  public static final int DELETE_AFTER_CONVERT_FLAG = 0x80;
  public static final int DISABLED_FLAG = 0x100;

  String getNameForType()
  {
    StringBuilder sb = new StringBuilder();
    if ((agentMask & LOVE_MASK) != 0) sb.append("Like");
    if ((agentMask & TITLE_MASK) != 0) sb.append("Title");
    if ((agentMask & CATEGORY_MASK) != 0) sb.append("Cat");
    if ((agentMask & ACTOR_MASK) != 0) sb.append("Person");
    if ((agentMask & RATED_MASK) != 0) sb.append("Rated");
    if ((agentMask & YEAR_MASK) != 0) sb.append("Year");
    if ((agentMask & PR_MASK) != 0) sb.append("PR");
    if ((agentMask & CHANNEL_MASK) != 0) sb.append("Chan");
    if ((agentMask & NETWORK_MASK) != 0) sb.append("Net");
    if ((agentMask & FIRSTRUN_MASK) != 0) sb.append("FirstRuns");
    if ((agentMask & RERUN_MASK) != 0) sb.append("ReRuns");
    if ((agentMask & KEYWORD_MASK) != 0) sb.append("Keyword");
    if (slotType == BigBrother.FULL_ALIGN) sb.append("FullSlot");
    if (slotType == BigBrother.DAY_ALIGN) sb.append("DaySlot");
    if (slotType == BigBrother.TIME_ALIGN) sb.append("TimeSlot");
    sb.append("Trend");
    return sb.toString();
  }

  Agent(int inID)
  {
    super(inID);
    agentID = id;
    wiz = Wizard.getInstance();
    createTime = Sage.time();
    weakAgents = new int[0];
    quality = "";
    autoConvertFormat = "";
  }
  Agent(DataInput in, byte ver, Map<Integer, Integer> idMap) throws IOException
  {
    super(in, ver, idMap);
    wiz = Wizard.getInstance();
    agentID = id;
    agentMask = in.readInt();
    title = wiz.getTitleForID(readID(in, idMap));
    Stringer primetitle = null;
    if (ver < 0x2A)
      primetitle = wiz.getPrimeTitleForID(readID(in, idMap));
    category = wiz.getCategoryForID(readID(in, idMap));
    subCategory = wiz.getSubCategoryForID(readID(in, idMap));
    person = wiz.getPersonForID(readID(in, idMap));
    if (ver >= 0x2F)
      in.readBoolean(); // padding to help avoid reversing db encryption algorithms
    rated = wiz.getRatedForID(readID(in, idMap));
    year = wiz.getYearForID(readID(in, idMap));
    pr = wiz.getPRForID(readID(in, idMap));
    setChannelName(in.readUTF());
    network = wiz.getNetworkForID(readID(in, idMap));
    createTime = in.readLong();
    if (ver < 0x4B)
    {
      int slotty = in.readInt();
      if (slotty != 0)
      {
        timeslots = new int[] { slotty };
      }
    }
    else
    {
      int numSlots = in.readInt();
      if (numSlots > 0)
      {
        timeslots = new int[numSlots];
        for (int i = 0; i < numSlots; i++)
          timeslots[i] = in.readInt();
      }
    }
    slotType = in.readInt();
    weakAgents = new int[in.readInt()];
    for (int i = 0; i < weakAgents.length; i++)
      weakAgents[i] = readID(in, idMap);
    if (ver < 0x2D)
    {
      int numWeakAirings = in.readInt(); // legacy
      for (int i = 0; i < numWeakAirings; i++)
        readID(in, idMap);
    }
    quality = in.readUTF();
    if (quality != null && quality.length() > 0)
      quality = MMC.cleanQualityName(quality);
    if (ver > 0x24)
    {
      startPad = in.readLong();
      stopPad = in.readLong();
    }
    if (ver > 0x27)
      agentFlags = in.readInt();

    if (ver < 0x2A)
    {
      // Prime title conversion
      if (primetitle == null && title != null)
      {
        agentMask = agentMask | RERUN_MASK;
      }
      else if (primetitle != null)
      {
        title = wiz.getTitleForName(primetitle.name);
        agentMask = (agentMask & (~PRIME_MASK)) | FIRSTRUN_MASK | TITLE_MASK;
      }
    }
    if (ver > 0x2A)
      role = in.readByte();
    else if (person != null)
      role = Show.ALL_ROLES;
    if (ver > 0x33)
      keyword = in.readUTF();
    if (ver > 0x41)
      autoConvertFormat = in.readUTF();
    if (ver > 0x42)
    {
      String s = in.readUTF();
      if (s == null || s.length() == 0)
        autoConvertDest = null;
      else
        autoConvertDest = new File(IOUtils.convertPlatformPathChars(s));
    }
    if (ver > 0x43)
    {
      buildFavProps(in.readUTF());
    }
    resetHashes();
  }

  private void buildFavProps(String str)
  {
    if (str != null && str.length() > 0)
    {
      if (favProps == null)
        favProps = new Properties();
      else
        favProps.clear();
      int currNameStart = 0;
      int currValueStart = -1;
      for (int i = 0; i < str.length(); i++)
      {
        char c = str.charAt(i);
        if (c == '\\')
        {
          // Escaped character, so skip the next one
          i++;
          continue;
        }
        else if (c == '=')
        {
          // We found the name=value delimeter, set the value start position
          currValueStart = i + 1;
        }
        else if (c == ';' && currValueStart != -1)
        {
          // We're at the end of the name value pair, get their values!
          String name = sage.media.format.ContainerFormat.unescapeString(str.substring(currNameStart, currValueStart - 1));
          String value = sage.media.format.ContainerFormat.unescapeString(str.substring(currValueStart, i));
          currNameStart = i + 1;
          currValueStart = -1;
          favProps.setProperty(name, value);
        }
      }
    }
    else if (favProps != null)
      favProps.clear();
  }

  void write(DataOutput out, int flags) throws IOException
  {
    super.write(out, flags);
    boolean useLookupIdx = (flags & Wizard.WRITE_OPT_USE_ARRAY_INDICES) != 0;
    out.writeInt(agentMask);
    out.writeInt((title == null) ? 0 : (useLookupIdx ? title.lookupIdx : title.id));
    out.writeInt((category == null) ? 0 : (useLookupIdx ? category.lookupIdx : category.id));
    out.writeInt((subCategory == null) ? 0 : (useLookupIdx ? subCategory.lookupIdx : subCategory.id));
    out.writeInt((person == null) ? 0 : (useLookupIdx ? person.lookupIdx : person.id));
    out.writeBoolean(true); // padding to help avoid reversing db encryption algorithms
    out.writeInt((rated == null) ? 0 : (useLookupIdx ? rated.lookupIdx : rated.id));
    out.writeInt((year == null) ? 0 : (useLookupIdx ? year.lookupIdx : year.id));
    out.writeInt((pr == null) ? 0 : (useLookupIdx ? pr.lookupIdx : pr.id));
    out.writeUTF(chanName);
    out.writeInt((network == null) ? 0 : (useLookupIdx ? network.lookupIdx : network.id));
    out.writeLong(createTime);
    out.writeInt(timeslots == null ? 0 : timeslots.length);
    if (timeslots != null)
    {
      for (int i = 0; i < timeslots.length; i++)
      {
        out.writeInt(timeslots[i]);
      }
    }
    out.writeInt(slotType);
    out.writeInt(weakAgents.length);
    for (int i = 0; i < weakAgents.length; i++)
      out.writeInt(weakAgents[i]);
    out.writeUTF(quality);
    out.writeLong(startPad);
    out.writeLong(stopPad);
    out.writeInt(agentFlags);
    out.writeByte(role);
    out.writeUTF(keyword);
    out.writeUTF(autoConvertFormat);
    out.writeUTF(autoConvertDest == null ? "" : autoConvertDest.getAbsolutePath());
    if (favProps == null)
      out.writeUTF("");
    else
    {
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<Object, Object> ent : favProps.entrySet())
      {
        sb.append(sage.media.format.MediaFormat.escapeString(ent.getKey().toString()));
        sb.append('=');
        sb.append(sage.media.format.MediaFormat.escapeString(ent.getValue().toString()));
        sb.append(';');
      }
      out.writeUTF(sb.toString());
    }
  }

  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    sb.append("Agent[Trend=");
    sb.append(getNameForType());
    if (title != null) sb.append(", Title=" + title.name);
    if (category != null) sb.append(", Cat=" + category.name);
    if (subCategory != null) sb.append(", SubCat=" + subCategory.name);
    if (person != null) sb.append(", Person=" + person.name);
    if (rated != null) sb.append(", Rated=" + rated.name);
    if (year != null) sb.append(", Year=" + year.name);
    if (pr != null) sb.append(", PR=" + pr.name);
    if (chanName.length() != 0) sb.append(", Chan=" + chanName);
    if (network != null) sb.append(", Network=" + network.name);
    if (timeslots != null && timeslots.length > 0)
    {
      sb.append(", Timeslot=");
      for (int i = 0; i < timeslots.length; i++)
      {
        if (i != 0)
          sb.append(", ");
        sb.append(timeslots[i]);
      }
    }
    if (keyword.length() > 0) sb.append(", Keyword=" + keyword);
    if (negator) sb.append(" NEGATOR ");
    sb.append(" id=");
    sb.append(agentID);
    sb.append(" watchProb=");
    sb.append(watchProb);
    sb.append(" numWatchedAirs=");
    sb.append(lastnumWatchedAirs);
    sb.append(" numWastedAirs=");
    sb.append(lastnumWastedAirs);
    sb.append(" numManualWaste=");
    sb.append(lastnumManualWaste);

    sb.append(" createTime=");
    sb.append(Sage.df(createTime));
    if (stopPad != 0)
    {
      sb.append(" stopPad=").append(stopPad);
    }
    if (startPad != 0)
      sb.append(" startPad=").append(startPad);
    sb.append(" del=").append((testAgentFlag(DONT_AUTODELETE_FLAG) ? "manual" : "auto"));
    int keepAtMost = getAgentFlag(KEEP_AT_MOST_MASK);
    if (keepAtMost > 0)
      sb.append(" keep=").append(keepAtMost);
    sb.append(" enabled=").append(!testAgentFlag(DISABLED_FLAG));
    sb.append(']');
    return sb.toString();
  }

  // DO NOT use this to change the channel name; only use it when creating the object to set the internal ch array
  protected void setChannelName(String inName)
  {
    chanName = inName;
    if (chanName != null && chanName.length() > 0)
    {
      StringTokenizer toker = new StringTokenizer(chanName, ";,");
      chanNames = new String[toker.countTokens()];
      for (int i = 0; i < chanNames.length; i++)
        chanNames[i] = toker.nextToken();
    }
    else
      chanNames = null;
  }

  void bully(Agent weaker)
  {
    if (weaker == this) return;
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      for (int i = 0; i < weakAgents.length; i++)
        if (weakAgents[i] == weaker.id)
          return;

      /*
       * We need to ensure consistency in Agent priorities whenever we create a new Agent
       * The first thing to do is find all of the recursive weak children of the Agent
       * we're about to make our underling.
       */
      List<Agent> allWeaklings = new ArrayList<Agent>();
      allWeaklings.add(weaker);
      for (int i = 0; i < allWeaklings.size(); i++)
      {
        Agent bond = allWeaklings.get(i);
        for (int j = 0; j < bond.weakAgents.length; j++)
        {
          Agent foo = wiz.getAgentForID(bond.weakAgents[j]);
          if (foo != null && !allWeaklings.contains(foo))
            allWeaklings.add(foo);
        }
      }
      /*
       * Now go through the list of all of the weaklings. Any Agents in that list who have a
       * weakAgent that is 'this', must have that relationship removed, or an inconsistency will result.
       */
      for (int i = 0; i < allWeaklings.size(); i++)
      {
        Agent bond = allWeaklings.get(i);
        for (int j = 0; j < bond.weakAgents.length; j++)
          if (bond.weakAgents[j] == id)
          {
            bond.unbully(this);
            break;
          }
      }

      int[] newWeaks = new int[weakAgents.length + 1];
      System.arraycopy(weakAgents, 0, newWeaks, 0, weakAgents.length);
      newWeaks[weakAgents.length] = weaker.id;
      weakAgents = newWeaks;
      wiz.logUpdate(this, Wizard.AGENT_CODE);
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
  }

  void unbully(Agent weaker)
  {
    unbully(weaker.id);
  }
  void unbully(int weakerID)
  {
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      int i = 0;
      for (i = 0; i < weakAgents.length; i++)
        if (weakAgents[i] == weakerID)
          break;
      if (i == weakAgents.length) return;
      int[] newWeaks = new int[weakAgents.length - 1];
      int j = 0;
      for (i = 0; i < weakAgents.length; i++)
        if (weakAgents[i] != weakerID)
          newWeaks[j++] = weakAgents[i];
      weakAgents = newWeaks;
      wiz.logUpdate(this, Wizard.AGENT_CODE);
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
  }

  // We can use any of these to narrow down a keyword or timeslot search. We should often be able to
  // at least limit the search to a channel, channels, first runs or reruns.
  private boolean hasSpecificCriteria()
  {
    return title != null || testAgentFlag(Agent.FIRSTRUN_MASK) || testAgentFlag(Agent.RERUN_MASK) ||
      person != null || category != null || subCategory != null || chanName.length() > 0 ||
      (chanNames != null && chanNames.length > 0) || network != null || rated != null ||
      year != null || pr != null;
  }

  private void optimizedFollowsTrend(boolean mustBeViewable, boolean controlCPUUsage,
                                     boolean skipKeyword, boolean ignoreDisabledFlag,
                                     Map<Integer, Airing[]> airingMap, DBObject allAirings[],
                                     Carny.CarnyCache cache, boolean countManualWasted, String mapName)
  {
    // We must have a cache to use this method. This will throw the exception immediately if we do
    // not.
    final StringBuilder sbCache = cache.sbCache;
    final int[] hashes = getHashes();

    // For keyword and timeslot trends with no other limiting factors, we need to check everything
    // since we haven't worked out a way to optimize this yet. Fortunately these are not as common
    // as title and people agents. Skip keyword doesn't apply here because it's not an optimization
    // that implies we will get anything from the map.
    boolean fullCheck = ((keyword.length() > 0 ||
      (slotType != 0 && timeslots != null && timeslots.length > 0)) && !hasSpecificCriteria());

    // If an agent has an interest in the hash 0, that means it doesn't actually know what it wants
    // for at least one of its properties and that means we must do a full check.
    if (!fullCheck)
    {
      for (int i = 0; i < hashes.length; i++)
      {
        if (hashes[i] == 0)
        {
          fullCheck = true;
          break;
        }
      }
    }

    if (countManualWasted)
    {
      cache.processedWaste.clear();
      cache.manualWasted = 0;
    }

    if (fullCheck || airingMap == null)
    {
      // This is here to help spell out any anything that's not being optimized when we are testing.
      if (VERIFY_AIRING_OPTIMIZATION && airingMap != null)
      {
        if (Sage.DBG) System.out.println("Unable to use optimized query: " + toString());
      }

      int searchStart = cache.useWorkCache.offset;
      int searchEnd = cache.useWorkCache.size;
      // We do not worry about binary ordering when we are processing all airings because they are
      // already sorted that way and if we are performing a second pass, it's much faster to just
      // check if we need it, then append it. If we need to go for a third pass (which never
      // happens), we would just sort and go again.
      if (countManualWasted)
      {
        for (int i = 0; i < allAirings.length; i++)
        {
          Airing airing = (Airing) allAirings[i];
          boolean contains = cache.useWorkCache.binarySearch(searchStart, searchEnd, airing) >= 0;
          if (!contains && followsTrend(airing, mustBeViewable, sbCache, skipKeyword, ignoreDisabledFlag))
          {
            cache.useWorkCache.ensureAddCapacity(1);
            cache.useWorkCache.add(airing);
            contains = true;
          }

          if (contains)
          {
            Wasted wasted = wiz.getWastedForAiring(airing);
            if (wasted != null && wasted.isManual())
              cache.manualWasted++;
          }

          if ((i % CPU_CONTROL_MOD_COUNT) == 0 && controlCPUUsage)
            try {Thread.sleep(Carny.SLEEP_PERIOD);} catch (Exception e) {}
        }
      }
      else
      {
        for (int i = 0; i < allAirings.length; i++)
        {
          Airing airing = (Airing) allAirings[i];
          if (followsTrend(airing, mustBeViewable, sbCache, skipKeyword, ignoreDisabledFlag))
          {
            cache.useWorkCache.ensureAddCapacity(1);
            cache.useWorkCache.add(airing);
          }

          if ((i % CPU_CONTROL_MOD_COUNT) == 0 && controlCPUUsage)
            try {Thread.sleep(Carny.SLEEP_PERIOD);} catch (Exception e) {}
        }
      }
    }
    else
    {
      // We need a copy of the list before we make changes to ensure that the long route will be
      // starting with the exact same data. We don't need to worry about the offset here since if
      // we copy everything, the offset still takes us to the same location. This is also only for
      // verification.
      Carny.CacheList fullTrend = VERIFY_AIRING_OPTIMIZATION ? cache.useWorkCache.copy() : null;
      Carny.CacheList processedWaste = cache.processedWaste;

      for (int i = -1, hashesSize = hashes.length; i < hashesSize; i++)
      {
        int airingLength;
        Airing airings[];
        switch (i)
        {
          case -1:
            // Hash code 0 is special and covers anything that might not have been correctly hashed,
            // so we always need to check these.
            airings = airingMap.get(0);
            if (airings == null)
              continue;
            airingLength = airings.length;
            break;
          default:
            airings = airingMap.get(hashes[i]);
            if (airings == null)
              continue;
            airingLength = airings.length;
            // If one matches, often most of them will unless it is 0 which is a little more random
            // in nature. Testing showed that this consistently reduced time by a measurable amount.
            // We could have based this on the largest array when we started, but the reality is
            // that we do not know what worker will end up dealing with a large set until it
            // happens, so we always deal with last minute adjustments like this.
            cache.useWorkCache.ensureAddCapacity(airingLength);
        }

        // If we get airings from more than one array, we could have duplicates. All of the arrays
        // in the map are already binary sorted, so no sort is required initially.
        if (cache.useWorkCache.apparentSize() == 0)
        {
          for (int j = 0; j < airingLength; j++)
          {
            Airing airing = airings[j];
            if (followsTrend(airing, mustBeViewable, sbCache, skipKeyword, ignoreDisabledFlag))
            {
              // For 0, we just check and resize as we go.
              if (i == -1)
                cache.useWorkCache.ensureAddCapacity(1);

              cache.useWorkCache.add(airing);

              if (countManualWasted)
              {
                Wasted wasted = wiz.getWastedForAiring(airing);
                if (wasted != null && wasted.isManual())
                  cache.manualWasted++;
                processedWaste.add(airing);
              }
            }
          }
        }
        else
        {
          for (int j = 0; j < airingLength; j++)
          {
            Airing airing = airings[j];
            // Inserting airings to maintain binary searching turns out to not be nearly as
            // expensive as not being able to do binary searches for duplicate airings. I tried also
            // excluding previously processed airings using a HashSet and a binary searched array
            // (faster of the two), but it was still faster to re-processing anything that's not
            // already in the cache and did not clear followsTrend() because of the volume. This is
            // also why we don't try to consolidate all of the airings before we iterate them here.
            int index;
            if ((index = cache.useWorkCache.binarySearch(airing)) < 0 &&
              followsTrend(airing, mustBeViewable, sbCache, skipKeyword, ignoreDisabledFlag))
            {
              // For 0, we just check and resize as we go.
              if (i == -1)
                cache.useWorkCache.ensureAddCapacity(1);

              index = -(index + 1);
              cache.useWorkCache.add(index, airing);
            }

            // We are interesting in anything that has been inserted already or was just inserted,
            // but not if we have already processed it for manual waste.
            if (countManualWasted && index >= 0 && processedWaste.binaryAdd(airing))
            {
              Wasted wasted = wiz.getWastedForAiring(airing);
              if (wasted != null && wasted.isManual())
                cache.manualWasted++;
            }
          }
        }
      }

      if (VERIFY_AIRING_OPTIMIZATION)
      {
        // Cache this value in case we might come back with a different value.
        int optManualWaste = cache.manualWasted;
        Carny.CacheList optAirWorkCache = cache.useWorkCache;
        cache.useWorkCache = fullTrend;
        // If we don't provide a map, a full search is always executed. Also don't provide the cache
        // or we will overwrite the results of this first run.
        optimizedFollowsTrend(mustBeViewable, controlCPUUsage, skipKeyword, ignoreDisabledFlag,
          null, allAirings, cache, countManualWasted, mapName);

        if (optManualWaste != cache.manualWasted)
        {
          if (Sage.DBG) System.out.println(
            "Optimized manual waste " + optManualWaste + " != full manual waste " + cache.manualWasted);
        }

        // Restore the old cache and manual wasted value.
        cache.useWorkCache = optAirWorkCache;
        cache.manualWasted = optManualWaste;

        // We could make this a lot better, but this ia a non-production verification mode.
        List<Airing> optList = cache.useWorkCache.getList();
        List<Airing> fullTrendList = fullTrend.getList();

        if (optAirWorkCache.size != fullTrend.size || !optList.equals(fullTrendList))
        {
          if (optAirWorkCache.size != fullTrend.size)
          {
            if (Sage.DBG) System.out.println(
              "Optimized size " + optAirWorkCache.size + " != full size " + fullTrend.size);
          }

          Set<Airing> diffs = new HashSet<>(optList);
          diffs.removeAll(fullTrendList);
          if (diffs.size() == 0)
          {
            diffs = new HashSet<>(fullTrendList);
            diffs.removeAll(optList);
          }
          if (diffs.size() > 0)
          {
            // This process is multi-threaded, so we insert our own new lines to ensure all of
            // of the desired details will be adjacent.
            StringBuilder stringBuilder = new StringBuilder("Trend optimized map ");
            stringBuilder.append(mapName);
            stringBuilder.append(" for agent ").append(toString()).append(" is missing ").append("\r\n");
            stringBuilder.append(diffs).append("\r\n");

            Set<Integer> usedHashes = new HashSet<>();
            Set<Integer> ignoredHashes = new HashSet<>();
            Set<Integer> searchedHashes = new HashSet<>();
            for (int hash : hashes)
              searchedHashes.add(hash);

            main_loop:
            for (Iterator<Airing> iterator = diffs.iterator(); iterator.hasNext(); )
            {
              Airing diff = iterator.next();
              Set<Integer> thisAirHashes = new HashSet<>();
              int stringReset = stringBuilder.length();
              for (Map.Entry<Integer, Airing[]> entry : airingMap.entrySet())
              {
                if (searchedHashes.contains(entry.getKey()))
                {
                  usedHashes.add(entry.getKey());
                  stringBuilder.setLength(stringReset);
                  continue main_loop;
                }

                // This is used for testing, so we are choosing convenience over performance.
                if (Arrays.asList(entry.getValue()).contains(diff))
                {
                  stringBuilder.append("Related Airings: ");
                  stringBuilder.append(entry.getKey()).append(':');
                  for (int i = 0; i < entry.getValue().length; i++)
                  {
                    Airing airing = entry.getValue()[i];
                    Show show = airing.getShow();
                    if (show != null && searchedHashes.contains(show.title.ignoreCaseHash))
                      stringBuilder.append("****");
                    stringBuilder.append(show.title.ignoreCaseHash).append(' ').append(show.title.name).append(',');
                  }
                  stringBuilder.append("\r\n");
                }
              }
              ignoredHashes.addAll(thisAirHashes);
            }
            stringBuilder.append("\r\n");
            ignoredHashes.removeAll(usedHashes);

            // If we have no ignored hashes, then we have something we are not hashing or not
            // hashing correctly.
            stringBuilder.append("Ignored Hashes: ").append(ignoredHashes).append("\r\n");
            stringBuilder.append("Used Hashes: ").append(usedHashes).append("\r\n");
            stringBuilder.append("Searched Hashes: ").append(searchedHashes).append("\r\n");
            System.out.println(stringBuilder.toString());
          }
        }
      }
    }
  }

  /*
   * The related airings don't change unless there's an update to the DB, so
   * cache this call.
   * This gets called from the AgentBrowser GUI as well as the Carny's main
   * thread, that's why it needs to be synced.
   */

  /**
   * Get all airings from the provided airings that this agent matches.
   *
   * @param allAirs All airings to be considered by this agent.
   * @param controlCPUUsage If <code>true</code>, this enables CPU usage throttling. This should
   *                        only be used when processing a large number of agents and we do not want
   *                        to potentially disrupt foreground tasks.
   * @param ignoreDisabledFlag If <code>true</code>, treat this agent as if it is enabled, even if it is
   *                       not.
   * @param sbCache This is an persistent StringBuffer instance for re-use through many iterations.
   * @return Airings that match this agent.
   */
  public Airing[] getRelatedAirings(DBObject[] allAirs, boolean controlCPUUsage,
                                    boolean ignoreDisabledFlag, StringBuilder sbCache)
  {
    boolean legacyKeyword = Sage.getBoolean("use_legacy_keyword_favorites", true);
    return getRelatedAirings(
      allAirs, controlCPUUsage, ignoreDisabledFlag, legacyKeyword, null, null, sbCache);
  }

  /**
   * Get all airings from the provided airings that this agent matches.
   *
   * @param allAirs All airings to be considered by this agent.
   * @param controlCPUUsage If <code>true</code>, this enables CPU usage throttling. This should
   *                        only be used when processing a large number of agents and we do not want
   *                        to potentially disrupt foreground tasks.
   * @param ignoreDisabledFlag If <code>true</code>, treat this agent as if it is enabled, even if it is
   *                       not.
   * @param legacyKeyword Do not use Lucene to optimize keyword searches.
   * @param cache This is an optional shared {@link sage.Carny.CarnyCache} object.
   * @param sbCache This is an persistent StringBuffer instance for re-use through many iterations.
   * @return Airings that match this agent.
   */
  public Airing[] getRelatedAirings(DBObject[] allAirs, boolean controlCPUUsage,
                                    boolean ignoreDisabledFlag, boolean legacyKeyword,
                                    Map<Integer, Airing[]> airingMap, Carny.CarnyCache cache,
                                    StringBuilder sbCache)
  {
    if (allAirs == null)
      return Pooler.EMPTY_AIRING_ARRAY;
    if (sbCache == null)
      sbCache = new StringBuilder();

    List<Airing> rv = null;
    if (cache == null)
      rv = new ArrayList<Airing>();
    else
      cache.useWorkCache.clear();

    boolean keywordTest = !legacyKeyword && (this.agentMask&(KEYWORD_MASK)) == (KEYWORD_MASK);
    if(keywordTest)
    {
      // If we're only doing a keyword mask, speed it up via Lucene
      Show[] shows = wiz.searchShowsByKeyword(getKeyword());
      for (Show show : shows)
      {
        Airing[] airings = wiz.getAirings(show, 0);
        for (Airing a : airings)
        {
          if (followsTrend(a, true, sbCache, true, ignoreDisabledFlag))
          {
            if (cache != null)
              cache.useWorkCache.add(a);
            else
              rv.add(a);
          }
        }
      }
    }
    else
    {
      if (cache != null)
      {
        optimizedFollowsTrend(true, controlCPUUsage, false, ignoreDisabledFlag, airingMap,
          allAirs, cache, false, "allAirs");
      }
      else
      {
        for (int i = 0; i < allAirs.length; i++)
        {
          Airing a = (Airing) allAirs[i];
          if (a == null) continue;
          if (followsTrend(a, true, sbCache))
            rv.add(a);
          if ((i % CPU_CONTROL_MOD_COUNT) == 0 && controlCPUUsage)
            try {Thread.sleep(Carny.SLEEP_PERIOD);} catch (Exception e) {}
        }
      }
    }

    // We don't return anything if we used the cache. The data is in the cache. This needs to either
    // be more elegant or the cache should always contain the return.
    return cache != null ? Pooler.EMPTY_AIRING_ARRAY : rv.toArray(Pooler.EMPTY_AIRING_ARRAY);
  }

  boolean validate()
  {
    if ((((agentMask & LOVE_MASK) == LOVE_MASK) && agentMask != LOVE_MASK) ||
        Carny.getInstance().isBaseTrend(agentMask))
    {
      if ((agentMask & TITLE_MASK) != 0 && title == null) return false;
      if ((agentMask & CATEGORY_MASK) != 0 && category == null)  return false;
      if ((agentMask & ACTOR_MASK) != 0 && person == null) return false;
      if ((agentMask & RATED_MASK) != 0 && rated == null) return false;
      if ((agentMask & YEAR_MASK) != 0 && year == null) return false;
      if ((agentMask & PR_MASK) != 0 && pr == null) return false;
      if ((agentMask & CHANNEL_MASK) != 0 && chanName.length() == 0) return false;
      if ((agentMask & NETWORK_MASK) != 0 && network == null) return false;
      if ((agentMask & KEYWORD_MASK) != 0 && keyword.length() == 0) return false;
      return true;
    }
    else
      return false;
  }

  synchronized void update(DBObject fromMe)
  {
    synchronized (hashesLock)
    {
      Agent bond = (Agent) fromMe;
      agentMask = bond.agentMask;
      createTime = bond.createTime;
      title = bond.title;
      category = bond.category;
      subCategory = bond.subCategory;
      setChannelName(bond.chanName);
      network = bond.network;
      rated = bond.rated;
      year = bond.year;
      pr = bond.pr;
      person = bond.person;
      slotType = bond.slotType;
      timeslots = (bond.timeslots == null ? null : ((int[]) bond.timeslots.clone()));
      weakAgents = bond.weakAgents.clone();
      quality = bond.quality;
      autoConvertFormat = bond.autoConvertFormat;
      autoConvertDest = bond.autoConvertDest;
      startPad = bond.startPad;
      stopPad = bond.stopPad;
      agentFlags = bond.agentFlags;
      role = bond.role;
      keyword = bond.keyword;
      if (bond.favProps != null)
        favProps = (Properties) bond.favProps.clone();
      else
        favProps = null;
      resetHashes();
    }
    super.update(fromMe);
  }

  public boolean isFirstRunsOnly() { return (agentMask & FIRSTRUN_MASK) == FIRSTRUN_MASK; }
  public boolean isReRunsOnly() { return (agentMask & RERUN_MASK) == RERUN_MASK; }
  public boolean firstRunsAndReruns() { return !isFirstRunsOnly() && !isReRunsOnly(); }
  public String getTitle() { return (title == null) ? "" : title.name; }
  // Title to be compared must be lowercase.
  public boolean isSameTitle(String title) { return (title == null) ? false : title.equalsIgnoreCase(title); }
  public String getCategory() { return (category == null) ? "" : category.name; }
  public String getSubCategory() { return (subCategory == null) ? "" : subCategory.name; }
  public String getPerson() { return (person == null) ? "" : person.name; }
  public Person getPersonObj() { return person; }
  public String getRated() { return (rated == null) ? "" : rated.name; }
  public int getRole() { return role; }
  public String getYear() { return (year == null) ? "" : year.name; }
  public String getPR() { return (pr == null) ? "" : pr.name; }
  public String getChannelName() { return chanName; }
  public String getNetwork() { return (network == null) ? "" : network.name; }
  public int[] getTimeslots() { return timeslots; }
  public int getSlotType() { return slotType; }
  public String getKeyword() { return keyword; }

  public boolean followsTrend(Airing air, boolean mustBeViewable, StringBuilder sbCache)
  {
    return followsTrend(air, mustBeViewable, sbCache, false, false);
  }

  public boolean followsTrend(Airing air, boolean mustBeViewable, StringBuilder sbCache,
                              boolean skipKeyword)
  {
      return followsTrend(air, mustBeViewable, sbCache, skipKeyword, false);
  }

  /**
   * Determine if the given airing meets the criteria for this Agent. (i.e. could the given Airing
   * be scheduled because of this Agent)
   *
   * @param air The Airing to be tested
   * @param mustBeViewable If true, the Airing must be viewable for this method to return true.
   *                       Viewable means a recording that can be watched, or a channel that can be
   *                       viewed.
   * @param sbCache A StringBuffer to be used by this method.  If null a new StringBuffer will be
   *                created. If non-null the buffer will be cleared and use.  When calling this
   *                method in a loop, the same StringBuffer can be used for each call to limit object
   *                creation and memory use.
   * @param skipKeyword If true, keyword matching is not considered
   * @param ignoreDisabledFlag If true, treat this agent as if it is enabled, even if it isn't.
   * @return true if the given Airing matches this Agent (given the parameter criteria), false otherwise.
   */
  public boolean followsTrend(Airing air, boolean mustBeViewable, StringBuilder sbCache,
                              boolean skipKeyword, boolean ignoreDisabledFlag)
  {
    /*
   * TODO(codefu): skipKeyword is a hack before showcase. It works since the other flags are AND
   * tested; but we can have Lucene do this for us
   */

    if (air == null) return false;
    Show s = air.getShow();
    if (s == null) return false;

    //A disabled agent doesn't match any airings
    if(!ignoreDisabledFlag && testAgentFlag(DISABLED_FLAG))
        return false;
    // Do not be case sensitive when checking titles!! We got a bunch of complaints about this on our forums.
    // Don't let null titles match all the Favorites!
    if (title != null && (s.title == null || !title.equalsIgnoreCase(s.title)))
      return false;
    if ((agentMask & FIRSTRUN_MASK) == FIRSTRUN_MASK && !air.isFirstRun())
      return false;
    if ((agentMask & RERUN_MASK) == RERUN_MASK && air.isFirstRun())
      return false;
    if (person != null)
    {
      int i = 0;
      for (; i < s.people.length; i++)
        if ((person == s.people[i] || person.equalsIgnoreCase(s.people[i])) &&
          (role == Show.ALL_ROLES || role == 0 || role == s.roles[i]))
          break;
      if (i == s.people.length) return false;
    }
    // For categories, we can match sub-main, main-sub, or whatever
    if (category != null)
    {
      if (s.categories.length == 0 || category != s.categories[0])
      {
        if (!category.toString().equals(s.getSubCategory()))
          return false;
      }
    }
    if (subCategory != null)
    {
      if (s.categories.length < 2 || subCategory != s.categories[1])
      {
        if (!subCategory.toString().equals(s.getCategory()))
          return false;
      }
    }
    if (chanName.length() > 0 && (air.getChannel() == null || !chanNameMatches(air.getChannel().name)))
      return false;
    if (network != null && (air.getChannel() == null || network != air.getChannel().network))
      return false;
    if (rated != null && rated != s.rated)
      return false;
    if (year != null && year != s.year)
      return false;
    if (pr != null && !pr.toString().equals(air.getParentalRating()))
      return false;
    if (slotType != 0 && timeslots != null && timeslots.length > 0)
    {
      boolean anyMatches = false;
      for (int i = 0; i < timeslots.length; i++)
      {
        if (BigBrother.alignsSlot(air, slotType, timeslots[i]))
        {
          anyMatches = true;
          break;
        }
      }
      if (!anyMatches)
        return false;
    }

    if ((agentMask & LOVE_MASK) == 0 && (agentMask & TITLE_MASK) == 0)
    {
      // Non-title tracks only match English language shows
      if (s.language != null && s.language.name != null &&
        !s.language.equalsIgnoreCase("english") && !s.language.equalsIgnoreCase("en"))
        return false;
    }

    // Don't track anything that's unviewable
    if (mustBeViewable && wiz.getFileForAiring(air) == null && !air.isViewable())
      return false;

    if (!skipKeyword && keyword.length() > 0)
    {
      boolean titleOnly = keyword.startsWith("TITLE:");
      String currKeyword = titleOnly ? keyword.substring("TITLE:".length()).trim() : keyword;
      // The fields in Show that we can test against are:
      // Title, Episode, Description, Year, People, category, subcategory, bonuses, ers, language
      StringBuilder fullShowTest;
      if (sbCache == null)
        fullShowTest = new StringBuilder(s.getTitle());
      else
      {
        sbCache.setLength(0);
        sbCache.append(s.getTitle());
        fullShowTest = sbCache;
      }
      if (!titleOnly)
      {
        fullShowTest.append('|'); fullShowTest.append(s.getEpisodeName());
        fullShowTest.append('|'); fullShowTest.append(s.getDesc());
        fullShowTest.append('|'); fullShowTest.append(s.getYear());
        for (int i = 0; i < s.people.length; i++)
        {
          fullShowTest.append('|'); fullShowTest.append(s.people[i].name);
        }
        fullShowTest.append('|'); fullShowTest.append(s.getCategory());
        fullShowTest.append('|'); fullShowTest.append(s.getSubCategory());
        for (int i = 2; i < s.categories.length; i++)
        {
          fullShowTest.append('|');
          fullShowTest.append(s.categories[i].name);
        }
        fullShowTest.append('|'); s.appendBonusesString(fullShowTest);
        fullShowTest.append('|'); s.appendExpandedRatingsString(fullShowTest);
        fullShowTest.append('|'); fullShowTest.append(s.getLanguage());
        fullShowTest.append('|'); fullShowTest.append(air.getChannelName());
        // This is a small point of contention because it uses the String resolver. If someone has
        // many threads running and many keyword favorites, this will collide a lot more than you
        // want on Part_Of_Parts.
        fullShowTest.append('|'); air.appendMiscInfo(fullShowTest);
        fullShowTest.append('|'); fullShowTest.append(s.getExternalID());
      }
      synchronized (this)
      {
        if (!currKeyword.equals(cachedKeywordForMats))
        {
          cachedKeywordForMats = currKeyword;

          // We break this up into groups by quotes, and then by spaces
          List<String> subPats = new ArrayList<String>();
          StringBuilder currPat = new StringBuilder();
          boolean inQuote = false;
          for (int i = 0; i < cachedKeywordForMats.length(); i++)
          {
            char c = cachedKeywordForMats.charAt(i);
            if (c == '"')
            {
              if (inQuote)
              {
                inQuote = false;
                if (currPat.length() > 0)
                {
                  subPats.add(currPat.toString());
                  currPat = new StringBuilder();
                }
              }
              else
              {
                if (currPat.length() > 0)
                {
                  subPats.add(currPat.toString());
                  currPat = new StringBuilder();
                }
                inQuote = true;
              }
            }
            else if (c == ' ')
            {
              if (inQuote)
              {
                currPat.append(c);
              }
              else
              {
                if (currPat.length() > 0)
                {
                  subPats.add(currPat.toString());
                  currPat = new StringBuilder();
                }
              }
            }
            else
            {
              currPat.append(c);
            }
          }
          if (currPat.length() > 0)
            subPats.add(currPat.toString());
          //                if (Sage.DBG) System.out.println("Parsed Keyword from [" + lcKeyword + "] into " + subPats);
          keywordMatchers = new Matcher[subPats.size()];
          for (int i = 0; i < keywordMatchers.length; i++)
          {
            String currPatStr = subPats.get(i);
            // Use whole word searches
            char c0 = currPatStr.charAt(0);
            if (Character.isLetterOrDigit(c0) || c0 == '*' || c0 == '?')
              currPatStr = "\\b" + currPatStr;
            c0 = currPatStr.charAt(currPatStr.length() - 1);
            if (Character.isLetterOrDigit(c0) || c0 == '*' || c0 == '?')
              currPatStr = currPatStr + "\\b";
            currPatStr = currPatStr.replaceAll("\\*", ".*").replaceAll("\\?", "[^| ]");
            //              if (Sage.DBG) System.out.println("Regex string #" + i + "=" + currPatStr);
            try
            {
              keywordMatchers[i] = Pattern.compile(currPatStr, Pattern.CASE_INSENSITIVE).matcher(fullShowTest);
            }
            catch (Exception ex)
            {
              System.out.println("ERROR with regex expression " + currKeyword + " in Favorite of: " + ex);
            }
          }
          for (int i = 0; i < keywordMatchers.length; i++)
          {
            if (keywordMatchers[i] == null || !keywordMatchers[i].find())
              return false;
          }
        }
        else
        {
          for (int i = 0; i < keywordMatchers.length; i++)
          {
            if (keywordMatchers[i] == null)
              return false;
            keywordMatchers[i].reset(fullShowTest);
            if (!keywordMatchers[i].find())
              return false;
          }
        }
      }
      //            if (Sage.DBG) System.out.println("Keyword found a match with:" + air + " text=" + fullShowTest);
    }

    return true;
  }

  // Returns false if it's an agent that shouldn't exist
  boolean calcWatchProb(boolean controlCPUUsage, Airing[] watchAirs, Airing[] wastedAirs,
                        boolean aggressiveNegativeProfiling, Carny.CarnyCache cache)
  {
    if ((agentMask & LOVE_MASK) == LOVE_MASK)
    {
      watchProb = 1;
      negator = false;

      // Save some last-used values to display for the agent; indicate as not set.
      lastnumWatchedAirs = -1;
      lastnumWastedAirs = -1;
      lastnumManualWaste = -1;

      return true;
    }

    final Map<Integer, Airing[]> watchAirsMap = cache.watchAirsMap;;
    final Map<Integer, Airing[]> wastedAirsMap = cache.wastedAirsMap;;
    final StringBuilder sbCache =  cache.sbCache;
    final boolean unoptimized = wastedAirs == null || wastedAirs == null || wastedAirsMap == null ||
      wastedAirsMap == null;

    // NOTE: We never call this method using an offset, so we can make some assumptions. If this
    // changes in the future, those assumptions need to be changed to account for the offset.
    cache.useWorkCache.clear();

    // There's three different things that affect this calculation.
    // 1. The Airings that are declared Watched that follow this trend
    // 2. The Airings that are Wasted that follow this trend

    /*
     * We do this with as few memory allocations as possible in order for it to not impact GC that much.
     * We first find all of the matching Watched Airings and put them in the storage array, tracking how many
     * are in there. Then we sort that by Airing ID.  Then we go through the Wasted by Airing ID and add to the
     * end of the array when we don't have overlap. We use a 'mergesort' type of scan to prevent overlap between
     * watched and wasted airings. We'll also need to track how many of them were Watched and how many were Wasted.
     */
    if (unoptimized)
    {
      DBObject[] watches = wiz.getRawAccess(Wizard.WATCH_CODE, (byte) 0);
      cache.useWorkCache.ensureAddCapacity(watches.length);

      for (int i = 0; i < watches.length; i++)
      {
        Watched currWatch = (Watched) watches[i];
        if (watches[i] != null && BigBrother.isFullWatch(currWatch))
        {
          Airing testA = currWatch.getAiring();
          if (followsTrend(testA, false, sbCache))
          {
            cache.useWorkCache.add(testA);
          }
        }
        if (controlCPUUsage && (i % CPU_CONTROL_MOD_COUNT) == 0)
          try{Thread.sleep(Carny.SLEEP_PERIOD);}catch(Exception e){}
      }

      cache.useWorkCache.sort();
    }
    else
    {
      // These results will already be sorted by ID.
      optimizedFollowsTrend(false, controlCPUUsage, false, false, watchAirsMap, watchAirs,
        cache, false, "watchAirsMap");
    }

    int numWatchedAirs = cache.useWorkCache.size;

    int numManualWaste = 0;
    if (unoptimized)
    {
      DBObject[] waste = wiz.getRawAccess(Wizard.WASTED_CODE, (byte) 0);
      cache.useWorkCache.ensureAddCapacity(waste.length);
      for (int i = 0; i < waste.length; i++)
      {
        Wasted currWaste = (Wasted) waste[i];
        if (waste[i] != null)
        {
          Airing testW = currWaste.getAiring();
          if (followsTrend(testW, false, sbCache))
          {
            if (currWaste.manual)
              numManualWaste++;
            if (cache.useWorkCache.binarySearch(numWatchedAirs, testW) < 0)
              cache.useWorkCache.add(testW);
          }
        }
        if (controlCPUUsage && (i % CPU_CONTROL_MOD_COUNT) == 0)
          try{Thread.sleep(Carny.SLEEP_PERIOD);}catch(Exception e){}
      }
    }
    else
    {
      optimizedFollowsTrend(false, controlCPUUsage, false, false, wastedAirsMap,
        wastedAirs, cache, true, "wastedAirsMap");

      numManualWaste = cache.manualWasted;
    }
    // This is correct because the air isn't considered wasted if it was also fully watched. We
    // accounted for manual waste (e.g. Don't Like) above. This is how the old behavior effectively
    // worked.
    int numWastedAirs = cache.useWorkCache.size - numWatchedAirs;

    if (VERIFY_AIRING_OPTIMIZATION)
    {
      Set<Airing> test = new HashSet<>(cache.useWorkCache.getList());
      if (test.size() != cache.useWorkCache.apparentSize())
      {
        System.out.println("WARNING: List contains redundant values: " + test.size() + " != " + cache.useWorkCache.apparentSize());
        StringBuilder stringBuilder = new StringBuilder("Redundant list: ");
        for (int i = cache.useWorkCache.offset; i < cache.useWorkCache.size; i++)
        {
          Airing object = cache.useWorkCache.data[i];
          stringBuilder.append(object.getID()).append(',');
        }
        System.out.println(stringBuilder);
      }
    }

    float watchCount = 0;
    int totalCount = 0;

    // Make all non-title based agents title unique for tracking
    boolean titleUniqueCount = (agentMask & TITLE_MASK) == 0;
    // These are used when doneShowMap needs an array. Reset the count so we can use them again.
    cache.resetNextAiringList();
    // It's faster not to cache and clear this map.
    Map<Show, Object> doneShowMap = new HashMap<>(); // Single Airing or List<Airing>
    Map<Stringer, MutableFloat> titleWatchMap = null;
    Map<Stringer, MutableInteger> titleAllMap =  null;

    if (titleUniqueCount)
    {
      titleWatchMap = cache.titleWatchMap;
      titleWatchMap.clear();
      titleAllMap = cache.titleAllMap;
      titleAllMap.clear();
    }

    next_air:
    for (int i = 0, size = cache.useWorkCache.size; i < size; i++)
    {
      Airing currAir = cache.useWorkCache.data[i];
      Show currShow = currAir.getShow();
      if (currShow == null) continue;
      Stringer theTit = null;
      if (titleUniqueCount)
        theTit = currShow.title;
      Object airSet = doneShowMap.get(currShow);
      if (airSet == null)
      {
        // The vast majority of these end up being a single airing, so we don't convert to an array
        // unless we actually need one. This can actually save a lot of time because otherwise we
        // sometimes allocated 100's of default size arrays just to accommodate 1 object per array.
        // This gets around the need to allocate anything in many cases.
        doneShowMap.put(currShow, currAir);
      }
      else if (airSet instanceof Airing)
      {
        Airing thisAiring = (Airing)airSet;
        if (BigBrother.areSameShow(thisAiring, currAir, true, sbCache))
          continue;

        List<Airing> newList = cache.getNextAiringList();
        newList.add(thisAiring);
        newList.add(currAir);
        doneShowMap.put(currShow, newList);
      }
      else if (airSet instanceof List)
      {
        for (int j = 0; j < ((List)airSet).size(); j++)
        {
          if (BigBrother.areSameShow((Airing)((List)airSet).get(j), currAir, true, sbCache))
            continue next_air;
        }

        ((List)airSet).add(currAir);
      }
      else
      {
        // This will never happen, but if somehow it can, we do not want to proceed.
        throw new IllegalArgumentException();
      }

      if (i < numWatchedAirs)
      {
        if (titleUniqueCount)
        {
          // Floats are not cached in any way like Integers, so this ends up creating tons of
          // objects when all we really need is a mutable float object.
          MutableFloat theFloat = titleWatchMap.get(theTit);
          if (theFloat == null)
          {
            theFloat = new MutableFloat(1);
            titleWatchMap.put(theTit, theFloat);
          }
          else
          {
            theFloat.increment(1);
          }

          MutableInteger theInt = titleAllMap.get(theTit);
          if (theInt == null)
          {
            theInt = new MutableInteger();
            titleAllMap.put(theTit, theInt);
          }
          else
          {
            theInt.increment(1);
          }
        }
        else
        {
          watchCount++;
          totalCount++;
        }
        continue;
      }
      else
      {
        if (titleUniqueCount)
        {
          MutableInteger theInt = titleAllMap.get(theTit);
          if (theInt == null)
          {
            theInt = new MutableInteger(1);
            titleAllMap.put(theTit, theInt);
          }
          else
          {
            theInt.increment(1);
          }
        }
        else
        {
          totalCount++;
        }
      }
    }
    int realTotalCount;
    if (titleUniqueCount)
    {
      for (Map.Entry<Stringer, MutableInteger> ent : titleAllMap.entrySet())
      {
        int total = ent.getValue().getValue();
        MutableFloat currW = titleWatchMap.get(ent.getKey());
        if (currW != null)
          watchCount += currW.getValue() / total;
        totalCount++;
      }
      realTotalCount = totalCount;
      totalCount = Math.max(totalCount, MIN_TOTAL);
    }
    else // This makes title agents need 3 watches to become 99%
    {
      realTotalCount = totalCount;
      totalCount = Math.max(totalCount, 3);
    }

    watchProb = watchCount/totalCount;
    watchProb = Math.min(MAX_WATCH_PROB, Math.max(MIN_WATCH_PROB, watchProb));
    if ((agentMask & TITLE_MASK) == 0)
      watchProb /= 2;

    // If there are more Don't Likes than Watched, set Watch Prob to 0 so this agent
    // won't be the cause of a recording w/o stopping some other agent from causing a recording.
    if ( (numWatchedAirs == 0 && numWastedAirs > 1) || (numManualWaste > numWatchedAirs) )
        watchProb = 0;

    // For a Title agent (or if aggressive negative profiling is on), then check whether
    // this agent should also prevent any other agent from recordings something; i.e.: set negator to true.
    negator = ((agentMask & TITLE_MASK) != 0 || aggressiveNegativeProfiling) &&
        ((numWatchedAirs == 0 && numWastedAirs > 1) ||
            (numManualWaste > numWatchedAirs));

    // Save some last-used values to display for the agent.
    lastnumWatchedAirs = numWatchedAirs;
    lastnumWastedAirs = numWastedAirs;
    lastnumManualWaste = numManualWaste;

    // We need two data points to exist if we're actor based
    if (realTotalCount == 0) return false;
    if ((agentMask & TITLE_MASK) != 0) return true;
    if (watchCount == 0) return false;
    if ((agentMask & ACTOR_MASK) != ACTOR_MASK) return true;
    return (watchCount >= 2);
  }

  public String getTypeName()
  {
    if (keyword != null && keyword.length() > 0)
    {
      if (keyword.startsWith("TITLE:"))
        return Sage.rez("Title Keyword");
      else
        return Sage.rez("Keyword");
    }
    if (person != null)
    {
      if (title != null)
        return Sage.rez("Team");
      else
        return Sage.rez("Person");
    }
    if (title != null)
      return Sage.rez("Show");
    return Sage.rez("Misc");
  }

  public String getCause()
  {
    return getCause(false);
  }

  public String getCause(boolean clean)
  {
    StringBuilder sb = new StringBuilder();
    if (title != null)
    {
      sb.append(title.name);
      sb.append(' ');
    }
    if (category != null)
    {
      sb.append(category.name);
      if (subCategory != null)
      {
        sb.append('/');
        sb.append(subCategory.name);
      }
      sb.append(' ');
    }
    if (person != null)
    {
      if (role > 0 && role < Show.ROLE_NAMES.length)
      {
        sb.append(Show.ROLE_NAMES[role]);
        sb.append(' ');
      }
      sb.append(person.name);
      sb.append(' ');
    }
    if (year != null)
    {
      sb.append(year.name);
      sb.append(' ');
    }
    if (pr != null)
    {
      sb.append(pr.name);
      sb.append(' ');
    }
    if (rated != null)
    {
      sb.append(rated.name);
      sb.append(' ');
    }
    if (network != null)
    {
      sb.append(network.name);
      sb.append(' ');
    }
    if (keyword.length() > 0)
    {
      if (clean)
      {
        if (keyword.startsWith("TITLE:"))
          sb.append(keyword.substring(6));
        else
          sb.append(keyword);
      }
      else
      {
        sb.append('[');
        sb.append(keyword);
        sb.append("] ");
      }
      sb.append(' ');
    }
    if ((agentMask & FIRSTRUN_MASK) == FIRSTRUN_MASK)
    {
      sb.append(Sage.rez("First_Runs"));
      sb.append(' ');
    }
    if ((agentMask & RERUN_MASK) == RERUN_MASK)
    {
      sb.append(Sage.rez("ReRuns"));
      sb.append(' ');
    }
    if (slotType != 0 && timeslots != null && timeslots.length > 0)
    {
      if (slotType == BigBrother.FULL_ALIGN)
      {
        // Coalesce the dates & times
        ArrayList<String> used = Pooler.getPooledArrayList();
        for (int i = 0; i < timeslots.length; i++)
        {
          String str = BigBrother.getTimeslotString(BigBrother.DAY_ALIGN, timeslots[i]);
          if (!used.contains(str))
          {
            used.add(str);
            sb.append(str);
            sb.append(' ');
          }
        }
        used.clear();
        for (int i = 0; i < timeslots.length; i++)
        {
          String str = BigBrother.getTimeslotString(BigBrother.TIME_ALIGN, timeslots[i]);
          if (!used.contains(str))
          {
            used.add(str);
            sb.append(str);
            sb.append(' ');
          }
        }
        Pooler.returnPooledArrayList(used);
      }
      else
      {
        for (int i = 0; i < timeslots.length; i++)
        {
          sb.append(BigBrother.getTimeslotString(slotType, timeslots[i]));
          sb.append(' ');
        }
      }
    }
    if (chanName.length() > 0)
    {
      sb.append(chanName);
    }
    return sb.toString().trim();
  }

  public boolean isNegativeNelly()
  {
    return negator;
  }

  public String getRecordingQuality()
  {
    return quality;
  }
  public void setRecordingQuality(String quality)
  {
    if (this.quality == quality || this.quality.equals(quality)) return;
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      this.quality = quality;
      wiz.logUpdate(this, Wizard.AGENT_CODE);
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
  }
  public String getAutoConvertFormat()
  {
    return autoConvertFormat;
  }
  public void setAutoConvertFormat(String format)
  {
    if (format == null) format = "";
    if (format.equals(autoConvertFormat)) return;
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      autoConvertFormat = format;
      wiz.logUpdate(this, Wizard.AGENT_CODE);
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
  }
  public File getAutoConvertDest()
  {
    return autoConvertDest;
  }
  public void setAutoConvertDest(File f)
  {
    if (f == autoConvertDest || (f != null && f.equals(autoConvertDest))) return;
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      autoConvertDest = f;
      wiz.logUpdate(this, Wizard.AGENT_CODE);
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
  }

  public long getStartPadding()
  {
    return startPad;
  }
  public void setStartPadding(long newPad)
  {
    if (startPad != newPad)
    {
      try {
        wiz.acquireWriteLock(Wizard.AGENT_CODE);
        startPad = newPad;
        wiz.logUpdate(this, Wizard.AGENT_CODE);
      } finally {
        wiz.releaseWriteLock(Wizard.AGENT_CODE);
      }
    }
  }

  public long getStopPadding()
  {
    return stopPad;
  }
  public void setStopPadding(long newPad)
  {
    if (stopPad != newPad)
    {
      try {
        wiz.acquireWriteLock(Wizard.AGENT_CODE);
        stopPad = newPad;
        wiz.logUpdate(this, Wizard.AGENT_CODE);
      } finally {
        wiz.releaseWriteLock(Wizard.AGENT_CODE);
      }
    }
  }

  public int getUID()
  {
    return id;
  }

  public int getAgentMask() { return agentMask; }
  public boolean isFavorite() { return (agentMask & LOVE_MASK) == LOVE_MASK; }
  public boolean testAgentFlag(int maskTest)
  {
    return (agentFlags & maskTest) == maskTest;
  }
  public int getAgentFlag(int whichFlag)
  {
    if (whichFlag == DONT_AUTODELETE_FLAG)
      return agentFlags & DONT_AUTODELETE_FLAG;
    else if (whichFlag == KEEP_AT_MOST_MASK)
      return (agentFlags & KEEP_AT_MOST_MASK) >> 1;
    else if (whichFlag == DELETE_AFTER_CONVERT_FLAG)
      return agentFlags & DELETE_AFTER_CONVERT_FLAG;
    else if (whichFlag == DISABLED_FLAG)
      return agentFlags & DISABLED_FLAG;
    else
      return 0;
  }
  void setAgentFlags(int maskBits, int values)
  {
    if (maskBits == KEEP_AT_MOST_MASK)
      values = values << 1;
    if ((agentFlags & maskBits) == values) return;
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      agentFlags = (agentFlags & (~maskBits)) | (maskBits & values);
      wiz.logUpdate(this, Wizard.AGENT_CODE);
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
  }

  private boolean chanNameMatches(String name)
  {
    if (chanNames == null) return false;
    for (int i = 0; i < chanNames.length; i++)
      if (chanNames[i].equals(name))
        return true;
    return false;
  }

  public String getProperty(String name)
  {
    if (favProps == null)
      return "";
    String rv = favProps.getProperty(name);
    return (rv == null) ? "" : rv;
  }

  public void setProperty(String name, String value)
  {
    if (value == null && (favProps == null || !favProps.containsKey(name)))
      return;
    if (value != null && favProps != null && value.equals(favProps.getProperty(name)))
      return;
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      if (value == null)
      {
        favProps.remove(name);
      }
      else
      {
        if (favProps == null)
          favProps = new Properties();
        favProps.setProperty(name, value);
      }
      wiz.logUpdate(this, Wizard.AGENT_CODE);
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
  }

  public Properties getProperties()
  {
    if (favProps == null)
      return new Properties();
    return (Properties) favProps.clone();
  }

  private void resetHashes()
  {
    synchronized (hashesLock)
    {
      hashes = Pooler.EMPTY_INT_ARRAY;
    }
  }

  // We need to synchronize here or the properties could be updated and we'll end up writing the
  // the wrong hashes.
  int[] getHashes()
  {
    synchronized (hashesLock)
    {
      int[] newHashes = hashes;
      if (newHashes != Pooler.EMPTY_INT_ARRAY)
        return newHashes;

      // We don't need to include 0 because that's always assumed in look ups.
      Set<Integer> searchedHashes = new HashSet<>();

      if (title != null)
      {
        searchedHashes.add(title.ignoreCaseHash);
      }

      if (title == null)
      {
        if (testAgentFlag(Agent.FIRSTRUN_MASK))
        {
          searchedHashes.add(Agent.FIRSTRUN_MASK);
        }
        if (testAgentFlag(Agent.RERUN_MASK))
        {
          searchedHashes.add(Agent.RERUN_MASK);
        }
      }

      if (person != null)
      {
        searchedHashes.add(person.ignoreCaseHash);
      }

      if (category != null)
      {
        searchedHashes.add(category.ignoreCaseHash);
      }

      if (subCategory != null)
      {
        searchedHashes.add(subCategory.ignoreCaseHash);
      }

      if (chanName.length() > 0)
      {
        searchedHashes.add(chanName.hashCode());
      }

      if (chanNames != null && chanNames.length > 0)
      {
        for (String chanName : chanNames)
        {
          searchedHashes.add(chanName.hashCode());
        }
      }

      if (network != null)
      {
        searchedHashes.add(network.ignoreCaseHash);
      }

      if (rated != null)
      {
        searchedHashes.add(rated.ignoreCaseHash);
      }

      if (year != null)
      {
        searchedHashes.add(year.ignoreCaseHash);
      }

      if (pr != null)
      {
        searchedHashes.add(pr.ignoreCaseHash);
      }

      newHashes = new int[searchedHashes.size()];
      int i = 0;
      for (Integer hash : searchedHashes)
      {
        newHashes[i++] = hash;
      }
      hashes = newHashes;
      return newHashes;
    }
  }

  final int agentID;
  int agentMask;
  transient float watchProb;
  transient int lastnumWatchedAirs;
  transient int lastnumWastedAirs;
  transient int lastnumManualWaste;
  //* zzzz
  Stringer title;
  Stringer category;
  Stringer subCategory;
  Person person;
  int role;
  Stringer rated;
  Stringer year;
  Stringer pr;
  String chanName = "";
  String[] chanNames;
  Stringer network;
  int[] timeslots;
  int slotType;
  long createTime;
  int[] weakAgents;
  String quality = "";
  String autoConvertFormat = "";
  File autoConvertDest;
  long startPad;
  long stopPad;
  int agentFlags;
  String keyword = "";
  Properties favProps;
  private Matcher[] keywordMatchers;
  private String cachedKeywordForMats;
  private transient Wizard wiz;
  private transient boolean negator = false;
  // This is set by whatever thread gets it first, so it must be volatile.
  private transient int[] hashes = Pooler.EMPTY_INT_ARRAY;
  private final Object hashesLock = new Object();
}
