package com.linkedin.clustermanager.controller.stages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.clustermanager.ClusterDataAccessor;
import com.linkedin.clustermanager.ClusterDataAccessor.IdealStateConfigProperty;
import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.PropertyType;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.model.IdealState;
import com.linkedin.clustermanager.model.ResourceGroup;
import com.linkedin.clustermanager.model.ResourceKey;
import com.linkedin.clustermanager.model.StateModelDefinition;
import com.linkedin.clustermanager.pipeline.AbstractBaseStage;
import com.linkedin.clustermanager.pipeline.StageException;
import com.linkedin.clustermanager.util.ZNRecordUtil;

/**
 * For resourceKey compute best possible (instance,state) pair based on
 * IdealState,StateModel,LiveInstance
 * 
 * @author kgopalak
 * 
 */
public class BestPossibleStateCalcStage extends AbstractBaseStage
{
  private static final Logger logger = Logger.getLogger(BestPossibleStateCalcStage.class.getName());

  @Override
  public void process(ClusterEvent event) throws Exception
  {
    ClusterManager manager = event.getAttribute("clustermanager");
    CurrentStateOutput currentStateOutput =
        event.getAttribute(AttributeName.CURRENT_STATE.toString());
    Map<String, ResourceGroup> resourceGroupMap =
        event.getAttribute(AttributeName.RESOURCE_GROUPS.toString());
    if (manager == null || currentStateOutput == null || resourceGroupMap == null)
    {
      throw new StageException("Missing attributes in event:" + event
          + " .Requires clustermanager|CURRENT_STATE|RESOURCE_GROUPS");
    }
    ClusterDataAccessor dataAccessor = manager.getDataAccessor();
    List<ZNRecord> liveInstances;
    liveInstances = dataAccessor.getChildValues(PropertyType.LIVEINSTANCES);
    List<ZNRecord> idealStates = dataAccessor.getChildValues(PropertyType.IDEALSTATES);
    List<ZNRecord> stateModelDefs = dataAccessor.getChildValues(PropertyType.STATEMODELDEFS);

    BestPossibleStateOutput bestPossibleStateOutput =
        compute(resourceGroupMap, liveInstances, idealStates, stateModelDefs, currentStateOutput);
    event.addAttribute(AttributeName.BEST_POSSIBLE_STATE.toString(), bestPossibleStateOutput);
  }

  protected BestPossibleStateOutput compute(Map<String, ResourceGroup> resourceGroupMap,
                                            List<ZNRecord> liveInstances,
                                            List<ZNRecord> idealStates,
                                            List<ZNRecord> stateModelDefs,
                                            CurrentStateOutput currentStateOutput)
  {
    // for each ideal state
    // read the state model def
    // for each resource
    // get the preference list
    // for each instanceName check if its alive then assign a state
    Map<String, ZNRecord> liveInstancesMap = ZNRecordUtil.convertListToMap(liveInstances);

    Map<String, ZNRecord> idealStatesMap = ZNRecordUtil.convertListToMap(idealStates);
    BestPossibleStateOutput output = new BestPossibleStateOutput();

    for (String resourceGroupName : resourceGroupMap.keySet())
    {
      logger.info("Processing resourceGroup:" + resourceGroupName);

      ResourceGroup resourceGroup = resourceGroupMap.get(resourceGroupName);
      // Ideal state may be gone. In that case we need to get the state model name
      // from the current state
      IdealState idealState;
      boolean idealStateExists = idealStatesMap.containsKey(resourceGroupName);
      if (!idealStateExists)
      {
        logger.info(" resourceGroup:" + resourceGroupName + " does not exist anymore");
        idealState = new IdealState(new ZNRecord(resourceGroupName));
      }
      else
      {
        // if ideal state is deleted, use a empty ZnRecord
        idealState = new IdealState(idealStatesMap.get(resourceGroupName));
      }

      String stateModelDefName =
          idealStateExists ? idealState.getStateModelDefRef()
              : currentStateOutput.getResourceGroupStateModelDef(resourceGroupName);

      StateModelDefinition stateModelDef = lookupStateModel(stateModelDefName, stateModelDefs);
      for (ResourceKey resource : resourceGroup.getResourceKeys())
      {
        Map<String, String> currentStateMap =
            currentStateOutput.getCurrentStateMap(resourceGroupName, resource);

        Map<String, String> bestStateForResource;
        if (idealState.getIdealStateMode() == IdealStateConfigProperty.CUSTOMIZED)
        {
          // TODO add computerBestStateForResourceInCustomizedMode()
          bestStateForResource = idealState.getInstanceStateMap(resource.getResourceKeyName());
        }
        else
        {
          List<String> instancePreferenceList =
              getPreferenceList(resource, idealState, liveInstances, stateModelDef);
          bestStateForResource =
              computeBestStateForResource(stateModelDef,
                                          instancePreferenceList,
                                          liveInstancesMap,
                                          currentStateMap);
        }

        output.setState(resourceGroupName, resource, bestStateForResource);
      }
    }
    return output;
  }

  private Map<String, String> computeBestStateForResource(StateModelDefinition stateModelDef,
                                                          List<String> instancePreferenceList,
                                                          Map<String, ZNRecord> liveInstancesMap,
                                                          Map<String, String> currentStateMap)
  {
    Map<String, String> instanceStateMap = new HashMap<String, String>();

    // if the ideal state is deleted, instancePreferenceList will be empty and
    // we should drop all resources.

    if (currentStateMap != null)
    {
      for (String instance : currentStateMap.keySet())
      {
        if (instancePreferenceList == null || !instancePreferenceList.contains(instance))
        {
          instanceStateMap.put(instance, "DROPPED");
        }
      }
    }
    if (instancePreferenceList == null)
    {
      return instanceStateMap;
    }

    List<String> statesPriorityList = stateModelDef.getStatesPriorityList();
    boolean assigned[] = new boolean[instancePreferenceList.size()];

    for (String state : statesPriorityList)
    {
      String num = stateModelDef.getNumInstancesPerState(state);
      int stateCount = -1;
      if ("N".equals(num))
      {
        stateCount = liveInstancesMap.size();
      }
      else if ("R".equals(num))
      {
        stateCount = instancePreferenceList.size();
      }
      else
      {
        try
        {
          stateCount = Integer.parseInt(num);
        }
        catch (Exception e)
        {
          logger.error("Invalid count for state:" + state + " ,count=" + num);
        }
      }
      if (stateCount > -1)
      {
        int count = 0;
        for (int i = 0; i < instancePreferenceList.size(); i++)
        {
          String instanceName = instancePreferenceList.get(i);

          boolean notInErrorState =
              currentStateMap == null || !"ERROR".equals(currentStateMap.get(instanceName));
          if (liveInstancesMap.containsKey(instanceName) && !assigned[i] && notInErrorState)
          {
            instanceStateMap.put(instanceName, state);
            count = count + 1;
            assigned[i] = true;
            if (count == stateCount)
            {
              break;
            }
          }
        }
      }
    }
    return instanceStateMap;
  }

  private List<String> getPreferenceList(ResourceKey resource,
                                         IdealState idealState,
                                         List<ZNRecord> liveInstances,
                                         StateModelDefinition stateModelDef)
  {
    List<String> listField =
        idealState.getPreferenceList(resource.getResourceKeyName(), stateModelDef);
    if (listField != null && listField.size() == 1 && "".equals(listField.get(0)))
    {
      ArrayList<String> list = new ArrayList<String>(liveInstances.size());
      for (ZNRecord liveInstance : liveInstances)
      {
        list.add(liveInstance.getId());
      }
      return list;
    }
    return listField;
  }

  private StateModelDefinition lookupStateModel(String stateModelDefRef,
                                                List<ZNRecord> stateModelDefs)
  {
    for (ZNRecord record : stateModelDefs)
    {
      if (record.getId().equals(stateModelDefRef))
      {
        return new StateModelDefinition(record);
      }
    }
    return null;
  }
}
