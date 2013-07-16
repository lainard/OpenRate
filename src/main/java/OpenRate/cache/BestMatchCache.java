/* ====================================================================
 * Limited Evaluation License:
 *
 * This software is open source, but licensed. The license with this package
 * is an evaluation license, which may not be used for productive systems. If
 * you want a full license, please contact us.
 *
 * The exclusive owner of this work is the OpenRate project.
 * This work, including all associated documents and components
 * is Copyright of the OpenRate project 2006-2013.
 *
 * The following restrictions apply unless they are expressly relaxed in a
 * contractual agreement between the license holder or one of its officially
 * assigned agents and you or your organisation:
 *
 * 1) This work may not be disclosed, either in full or in part, in any form
 *    electronic or physical, to any third party. This includes both in the
 *    form of source code and compiled modules.
 * 2) This work contains trade secrets in the form of architecture, algorithms
 *    methods and technologies. These trade secrets may not be disclosed to
 *    third parties in any form, either directly or in summary or paraphrased
 *    form, nor may these trade secrets be used to construct products of a
 *    similar or competing nature either by you or third parties.
 * 3) This work may not be included in full or in part in any application.
 * 4) You may not remove or alter any proprietary legends or notices contained
 *    in or on this work.
 * 5) This software may not be reverse-engineered or otherwise decompiled, if
 *    you received this work in a compiled form.
 * 6) This work is licensed, not sold. Possession of this software does not
 *    imply or grant any right to you.
 * 7) You agree to disclose any changes to this work to the copyright holder
 *    and that the copyright holder may include any such changes at its own
 *    discretion into the work
 * 8) You agree not to derive other works from the trade secrets in this work,
 *    and that any such derivation may make you liable to pay damages to the
 *    copyright holder
 * 9) You agree to use this software exclusively for evaluation purposes, and
 *    that you shall not use this software to derive commercial profit or
 *    support your business or personal activities.
 *
 * This software is provided "as is" and any expressed or impled warranties,
 * including, but not limited to, the impled warranties of merchantability
 * and fitness for a particular purpose are disclaimed. In no event shall
 * Tiger Shore Management or its officially assigned agents be liable to any
 * direct, indirect, incidental, special, exemplary, or consequential damages
 * (including but not limited to, procurement of substitute goods or services;
 * Loss of use, data, or profits; or any business interruption) however caused
 * and on theory of liability, whether in contract, strict liability, or tort
 * (including negligence or otherwise) arising in any way out of the use of
 * this software, even if advised of the possibility of such damage.
 * This software contains portions by The Apache Software Foundation, Robert
 * Half International.
 * ====================================================================
 */

package OpenRate.cache;

import OpenRate.configurationmanager.ClientManager;
import OpenRate.db.DBUtil;
import OpenRate.exception.InitializationException;
import OpenRate.lang.DigitTree;
import OpenRate.logging.LogUtil;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

/**
 * Please <a target='new' href='http://www.open-rate.com/wiki/index.php?title=Best_Match_Cache'>click here</a> to go to wiki page.
 * <br>
 * <p>
 * This class implements an example of a best match cached object
 * for use in a Zone lookup based on service code and number
 *
 * Zone information is read from a file during the "LoadCache" processing
 * which is triggered by the CacheFactory. The file is defined in the
 * properties. The format of the file is:
 *
 *     SERVICE;PREFIX_DIGITS;RESULT
 *     SERVICE;PREFIX_DIGITS;RESULT
 *     ...
 *
 * e.g.TEL;0039;Italy
 *     TEL;0049;Europe1
 *     TEL;0044;Europe1
 *     ...
 *
 * This module is intended to be used with the AbstractBestMatch process module,
 * which provides the control for reloading.
 *
 * @author i.sparkes
 */
public class BestMatchCache
     extends AbstractSyncLoaderCache
{
 /**
  * This stores all the cacheable data. The DigitTree class is
  * a way of storing numeric values for a best match search.
  * The cost of a search is linear with the number of digits
  * stored in the search tree
  */
  protected HashMap<String, DigitTree> GroupCache;
  private DigitTree prefixCache;

  // List of Services that this Client supports
  private final static String SERVICE_OBJECT_COUNT = "ObjectCount";
  private final static String SERVICE_GROUP_COUNT = "GroupCount";
  private final static String SERVICE_DUMP_MAP = "DumpMap";

  // This is the null result
  private ArrayList<String>     NoResult = new ArrayList<>();

 /** Constructor
  * Creates a new instance of the Group Cache. The group Cache
  * contains all of the Best Match Maps that are later cached. The lookup
  * is therefore performed within the group, retrieving the best
  * match for that group.
  */
  public BestMatchCache()
  {
    super();

    // Initialise the group cache
    GroupCache = new HashMap<>(50);

    // create the null result
    NoResult.add(DigitTree.NO_DIGIT_TREE_MATCH);
  }

// -----------------------------------------------------------------------------
// ------------------ Start of inherited Plug In functions ---------------------
// -----------------------------------------------------------------------------

 /**
  * Load the data from the defined file
  * @throws InitializationException
  */
  @Override
  public void loadDataFromFile() throws InitializationException
  {
    // Variable declarations
    int               ZonesLoaded = 0;
    BufferedReader    inFile;
    String            tmpFileRecord;
    String[]          ZoneFields;
    ArrayList<String> ChildData = null;
    int               childDataCounter;
    int               formFactor = 0;

    // Log that we are starting the loading
    getFWLog().info("Starting Best Match Cache Loading from File for cache <" + getSymbolicName() + ">");

    // Try to open the file
    try
    {
      inFile = new BufferedReader(new FileReader(CacheDataFile));
    }
    catch (FileNotFoundException exFileNotFound)
    {
      message = "Application is not able to read file : <" +
            CacheDataFile + ">";
      throw new InitializationException(message,exFileNotFound,getSymbolicName());
    }

    // File open, now get the stuff
    try
    {
      while (inFile.ready())
      {
        tmpFileRecord = inFile.readLine();

        if ((tmpFileRecord.startsWith("#")) |
            tmpFileRecord.trim().equals(""))
        {
          // Comment line, ignore
        }
        else
        {
          ZonesLoaded++;
          ZoneFields = tmpFileRecord.split(";");

          if (ZoneFields.length != formFactor)
          {
            // see if we are setting or changing
            if (formFactor != 0)
            {
              // this is a change - NO NO
              message = "Form factor change <" + CacheDataFile +
              "> in record <" + ZonesLoaded + ">. Originally got <" + formFactor +
              "> fields in a record, not getting <" + ZoneFields.length + ">";

              throw new InitializationException(message,getSymbolicName());
            }
            else
            {
              // setting
              formFactor = ZoneFields.length;
            }
          }

          if (ZoneFields.length < 3)
          {
            // There are not enough fields
            message = "Error reading input file <" + CacheDataFile +
            "> in record <" + ZonesLoaded + ">. Malformed Record.";

            throw new InitializationException(message,getSymbolicName());
          }

          if (ZoneFields.length >= 3)
          {
            // we have some child data
            ChildData = new ArrayList<>();

            for (childDataCounter = 2 ; childDataCounter < ZoneFields.length ; childDataCounter++)
            {
              ChildData.add(ZoneFields[childDataCounter]);
            }
          }
          addEntry(ZoneFields[0], ZoneFields[1], ChildData);

          // Update to the log file
          if ((ZonesLoaded % loadingLogNotificationStep) == 0)
          {
            message = "Best Match Data Loading: <" + ZonesLoaded +
                  "> configurations loaded for <" + getSymbolicName() + "> from <" +
                  CacheDataFile + ">";
            getFWLog().info(message);
          }
        }
      }
    }
    catch (IOException ex)
    {
      getFWLog().fatal(
            "Error reading input file <" + cacheDataSourceName +
            "> in record <" + ZonesLoaded + ">. IO Error.");
    }
    finally
    {
      try
      {
        inFile.close();
      }
      catch (IOException ex)
      {
        message = "Error closing input file <" + cacheDataSourceName + ">";
        throw new InitializationException(message,ex,getSymbolicName());
      }
    }

    getFWLog().info(
          "Best Match Data Loading completed. <" + ZonesLoaded +
          "> configuration lines loaded for <" + getSymbolicName() + " > from <"
          + CacheDataFile + ">");
    getFWLog().info(
          "Loaded <3> base fields and <" + (formFactor - 3) +
          "> additional data fields");
  }

 /**
  * Load the data from the defined Data Source
  * @throws InitializationException
  */
  @Override
  public void loadDataFromDB()
                      throws InitializationException
  {
    int               ZonesLoaded = 0;
    String            Group;
    String            DestinationPrefix;
    ArrayList<String> ChildData = null;
    int               childDataCounter;
    int               formFactor = 0;

    // Log that we are starting the loading
    getFWLog().info("Starting Best Match Cache Loading from DB for <" + getSymbolicName() + ">");

    // Try to open the DS
    JDBCcon = DBUtil.getConnection(cacheDataSourceName);

    // Now prepare the statements
    prepareStatements();

    // Execute the query
    try
    {
      mrs = StmtCacheDataSelectQuery.executeQuery();
    }
    catch (SQLException Sex)
    {
      message = "Error performing SQL for retieving Best Match data. message <" + Sex.getMessage() + ">";
      getFWLog().fatal(message);
      throw new InitializationException(message,getSymbolicName());
    }

    // loop through the results for the customer login cache
    try
    {
      mrs.beforeFirst();

      formFactor = mrs.getMetaData().getColumnCount();

      while (mrs.next())
      {
        ZonesLoaded++;
        Group = mrs.getString(1);
        DestinationPrefix = mrs.getString(2);

        if (formFactor < 3)
        {
          // There are not enough fields
          message = "Error reading input data from <" + cacheDataSourceName +
          "> in record <" + ZonesLoaded + ">. Not enough fields.";

          throw new InitializationException(message,getSymbolicName());
        }

        if (formFactor >= 3)
        {
          // we have some child data
          ChildData = new ArrayList<>();

          for (childDataCounter = 3 ; childDataCounter <= formFactor ; childDataCounter++)
          {
            ChildData.add(mrs.getString(childDataCounter));
          }
        }

        // Add the map
        addEntry(Group, DestinationPrefix, ChildData);

        // Update to the log file
        if ((ZonesLoaded % loadingLogNotificationStep) == 0)
        {
          message = "Best Match Data Loading: <" + ZonesLoaded +
                "> configurations loaded for <" + getSymbolicName() + "> from <" +
                cacheDataSourceName + ">";
          getFWLog().info(message);
        }
      }
    }
    catch (SQLException ex)
    {
      message = "Error opening Search Map Data for <" + cacheDataSourceName + ">";
      throw new InitializationException(message,ex,getSymbolicName());
    }

    // Close down stuff
    try
    {
      mrs.close();
      StmtCacheDataSelectQuery.close();
      JDBCcon.close();
    }
    catch (SQLException ex)
    {
      message = "Error closing Search Map Data connection for <" +
            cacheDataSourceName + ">";
      throw new InitializationException(message,ex,getSymbolicName());
    }

    getFWLog().info(
          "Best Match Data Loading completed. <" + ZonesLoaded +
          "> configuration lines loaded for <" + getSymbolicName() + "> from <" +
          cacheDataSourceName + ">");
    getFWLog().info("Loaded <3> base fields and <" + (formFactor - 3) +
          "> additional data fields");
  }

 /**
  * Load the data from the defined Data Source
  * @throws InitializationException
  */
  @Override
  public void loadDataFromMethod()
                      throws InitializationException
  {
    int               ZonesLoaded = 0;
    String            Group;
    String            DestinationPrefix;
    ArrayList<String> ChildData = null;
    int               childDataCounter;
    int               formFactor = 0;
    ArrayList<String> tmpMethodResult;

    // Log that we are starting the loading
    getFWLog().info("Starting Best Match Cache Loading from Method for <" + getSymbolicName() + ">");

    // Execute the user domain method
    Collection<ArrayList<String>> methodLoadResultSet;

    methodLoadResultSet = getMethodData(getSymbolicName(),CacheMethodName);

    Iterator<ArrayList<String>> methodDataToLoadIterator = methodLoadResultSet.iterator();

    // loop through the results for the customer login cache
    while (methodDataToLoadIterator.hasNext())
    {
      tmpMethodResult = methodDataToLoadIterator.next();

      formFactor = tmpMethodResult.size();

      if (formFactor < 3)
      {
        // There are not enough fields
        message = "Error reading input data from <" + cacheDataSourceName +
        "> in record <" + ZonesLoaded + ">. Not enough fields.";

        throw new InitializationException(message,getSymbolicName());
      }

      ZonesLoaded++;
      Group = tmpMethodResult.get(0);
      DestinationPrefix = tmpMethodResult.get(1);

      if (formFactor >= 3)
      {
        // we have some child data
        ChildData = new ArrayList<>();

        for (childDataCounter = 2 ; childDataCounter < formFactor ; childDataCounter++)
        {
          ChildData.add(tmpMethodResult.get(childDataCounter));
        }
      }

      // Add the map
      addEntry(Group, DestinationPrefix, ChildData);

      // Update to the log file
      if ((ZonesLoaded % loadingLogNotificationStep) == 0)
      {
        message = "Best Match Data Loading: <" + ZonesLoaded +
              "> configurations loaded for <" + getSymbolicName() + "> from <" +
              cacheDataSourceName + ">";
        getFWLog().info(message);
      }
    }

    getFWLog().info(
          "Best Match Cache Data Loading completed. " + ZonesLoaded +
          " configuration lines loaded from <" + cacheDataSourceName +
          ">");
    getFWLog().info(
          "Loaded <3> base fields and <" + (formFactor - 3) +
          "> additional data fields");
  }

  // -----------------------------------------------------------------------------
  // -------------------- Start of custom Plug In functions ----------------------
  // -----------------------------------------------------------------------------

 /**
  * Add a value into the BestMatchCache, defining the result
  * value that should be returned in the case of a (best) match.
  * The Digit Trees are divided by service
  *
  * @param Group The group for the zone
  * @param key The prefix
  * @param Results The result array
  * @throws InitializationException
  */
  public void addEntry(String Group, String key, ArrayList<String> Results)
    throws InitializationException
  {
    // See if we already have the digit tree for this service
    if (!GroupCache.containsKey(Group))
    {
      // Create the new Digit Tree
      prefixCache = new DigitTree();

      GroupCache.put(Group, prefixCache);

      try
      {
        prefixCache.addPrefix(key, Results);
      }
      catch (ArrayIndexOutOfBoundsException aiex)
      {
        message = "Error Adding Prefix <" + key + "> to group <" + Group + "> in module <" + getSymbolicName() + ">";
        throw new InitializationException(message,getSymbolicName());
      }
    }
    else
    {
      // Otherwise just add it to the existing Digit Tree
      prefixCache = GroupCache.get(Group);
      try
      {
        prefixCache.addPrefix(key, Results);
      }
      catch (ArrayIndexOutOfBoundsException aiex)
      {
        message = "Error Adding Prefix <" + key + "> to model <" + Group + "> in module <" + getSymbolicName() + ">";
        getFWLog().fatal(message);
        throw new InitializationException(message,getSymbolicName());
      }
    }
  }

 /**
  * Get a value from the BestMatchCache.
  * If we do not know the service, the result is automatically "no match".
  * The Digit Trees are divided by group
  *
  * @param Service The group
  * @param key The prefix
  * @return The result
  */
  public String getMatch(String Service, String key)
  {
    String Value;

    // Get the service if we know it
    prefixCache = GroupCache.get(Service);

    if (prefixCache != null)
    {
      Value = prefixCache.match(key);
    }
    else
    {
      // We don't know the service, so we cannot know the prefix
      Value = DigitTree.NO_DIGIT_TREE_MATCH;
    }

    return Value;
  }

 /**
  * Get a value from the BestMatchCache.
  * If we do not know the service, the result is automatically "no match".
  * The Digit Trees are divided by group
  *
  * @param Service The group
  * @param key The prefix
  * @return The result
  */
  public ArrayList<String> getMatchWithChildData(String Service, String key)
  {
    // Get the service if we know it
    prefixCache = GroupCache.get(Service);

    if (prefixCache != null)
    {
      return prefixCache.matchWithChildData(key);
    }
    else
    {
      // We don't know the service, so we cannot know the prefix
      return NoResult;
    }
  }

 /**
  * Clear down the cache contents in the case that we are ordered to reload
  */
  @Override
  public void clearCacheObjects()
  {
    GroupCache.clear();
  }

 /**
  * Dumps the entire contents of the cache to the Log.
  */
  protected void DumpMapData()
  {
    String      Helper;
    Iterator<String>    GroupIter;

    getFWLog().info("Dumping Map Data for BestMatchCache <" + getSymbolicName() + ">");
    getFWLog().info("Groups:");

    // Iterate thorough the entries in the group
    GroupIter = GroupCache.keySet().iterator();
    while (GroupIter.hasNext())
    {
      Helper = GroupIter.next();
      getFWLog().info("  " + Helper);
    }

    // Now dump the data
    GroupIter = GroupCache.keySet().iterator();
    while (GroupIter.hasNext())
    {
      Helper = GroupIter.next();
      getFWLog().info("Dumping group map data for <" + Helper + ">");

      // The rest of the data is horrible to extract - a problem for a rainy day
    }
  }

  // -----------------------------------------------------------------------------
  // ------------- Start of inherited IEventInterface functions ------------------
  // -----------------------------------------------------------------------------

 /**
  * registerClientManager registers the client module to the ClientManager class
  * which manages all the client modules available in this OpenRate Application.
  *
  * registerClientManager registers this class as a client of the ECI listener
  * and publishes the commands that the plug in understands. The listener is
  * responsible for delivering only these commands to the plug in.
  *
  */
  @Override
  public void registerClientManager() throws InitializationException
  {
    // Set the client reference and the base services first
    super.registerClientManager();

    //Register services for this Client
    ClientManager.getClientManager().registerClientService(getSymbolicName(), SERVICE_GROUP_COUNT, ClientManager.PARAM_DYNAMIC);
    ClientManager.getClientManager().registerClientService(getSymbolicName(), SERVICE_OBJECT_COUNT, ClientManager.PARAM_DYNAMIC);
    ClientManager.getClientManager().registerClientService(getSymbolicName(), SERVICE_DUMP_MAP, ClientManager.PARAM_DYNAMIC);
  }

 /**
  * processControlEvent is the method that will be called when an event
  * is received for a module that has registered itself as a client of the
  * External Control Interface
  *
  * @param Command - command that is understand by the client module
  * @param Init - we are performing initial configuration if true
  * @param Parameter - parameter for the command
  * @return The result string of the operation
  */
  @Override
  public String processControlEvent(String Command, boolean Init,
                                    String Parameter)
  {
    DigitTree   tmpPrefixCache;
    Collection<String>  tmpGroups;
    Iterator<String>    GroupIter;
    String      tmpGroupName;
    int         Objects = 0;
    int         ResultCode = -1;

    // Return the number of objects in the cache
    if (Command.equalsIgnoreCase(SERVICE_GROUP_COUNT))
    {
      return Integer.toString(GroupCache.size());
    }

    if (Command.equalsIgnoreCase(SERVICE_OBJECT_COUNT))
    {
      tmpGroups = GroupCache.keySet();
      GroupIter = tmpGroups.iterator();

      while (GroupIter.hasNext())
      {
        tmpGroupName = GroupIter.next();
        tmpPrefixCache = GroupCache.get(tmpGroupName);
        Objects += tmpPrefixCache.size();
      }

      return Integer.toString(Objects);
    }

    // Return the number of objects in the cache
    if (Command.equalsIgnoreCase(SERVICE_DUMP_MAP))
    {
      // onl< dump on a positive command
      if (Parameter.equalsIgnoreCase("true"))
      {
        DumpMapData();
      }

      ResultCode = 0;
    }

    if (ResultCode == 0)
    {
      getFWLog().debug(LogUtil.LogECICacheCommand(getSymbolicName(), Command, Parameter));

      return "OK";
    }
    else
    {
      // pass the event up the stack
      return super.processControlEvent(Command,Init,Parameter);
    }
  }
}
