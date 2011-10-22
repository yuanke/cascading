/*
 * Copyright (c) 2007-2011 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cascading.flow.stream;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import cascading.flow.FlowElement;
import cascading.flow.FlowProcess;
import cascading.flow.planner.FlowStep;
import cascading.pipe.Each;
import cascading.pipe.Every;
import cascading.pipe.Group;
import cascading.pipe.Pipe;
import cascading.tap.Tap;

/**
 *
 */
public abstract class StepStreamGraph extends StreamGraph
  {
  protected final FlowProcess flowProcess;
  protected final FlowStep step;

  public StepStreamGraph( FlowProcess flowProcess, FlowStep step )
    {
    this.flowProcess = flowProcess;
    this.step = step;
    }

  protected Object getProperty( String name )
    {
    return flowProcess.getProperty( name );
    }

  protected void handleDuct( FlowElement lhsElement, Duct lhsDuct )
    {
    List<FlowElement> successors = step.getSuccessors( lhsElement );

    if( !stopOnElement( lhsElement, successors ) )
      handleSuccessors( lhsDuct, successors );
    else
      addTail( lhsDuct );
    }

  protected abstract boolean stopOnElement( FlowElement lhsElement, List<FlowElement> successors );

  private void handleSuccessors( Duct lhsDuct, List<FlowElement> successors )
    {
    for( FlowElement rhsElement : successors )
      {
      Duct newRhsDuct = createDuctFor( rhsElement );
      Duct rhsDuct = findExisting( newRhsDuct );

      addPath( lhsDuct, rhsDuct );

      if( rhsDuct != newRhsDuct ) // don't keep going if we have already seen rhs
        continue;

      handleDuct( rhsElement, rhsDuct );
      }
    }

  private Duct createDuctFor( FlowElement element )
    {
    Duct rhsDuct = null;

    if( element instanceof Each )
      {
      if( ( (Each) element ).isFunction() )
        rhsDuct = new FunctionEachStage( flowProcess, (Each) element );
      else if( ( (Each) element ).isFilter() )
        rhsDuct = new FilterEachStage( flowProcess, (Each) element );
      else if( ( (Each) element ).isValueAssertion() )
        rhsDuct = new ValueAssertionEachStage( flowProcess, (Each) element );
      else
        throw new IllegalStateException( "unknown operation: " + ( (Each) element ).getOperation().getClass().getCanonicalName() );
      }
    else if( element instanceof Every )
      {
      if( ( (Every) element ).isBuffer() )
        rhsDuct = new BufferEveryWindow( flowProcess, (Every) element );
      else if( ( (Every) element ).isAggregator() )
        rhsDuct = new AggregatorEveryStage( flowProcess, (Every) element );
      else if( ( (Every) element ).isGroupAssertion() )
        rhsDuct = new GroupAssertionEveryStage( flowProcess, (Every) element );
      else
        throw new IllegalStateException( "unknown operation: " + ( (Every) element ).getOperation().getClass().getCanonicalName() );
      }
    else if( element instanceof Group )
      {
      if( ( (Group) element ).isGroupBy() )
        rhsDuct = createGroupByGate( (Group) element );
      else
        rhsDuct = createCoGroupGate( (Group) element );
      }
    else if( element instanceof Tap )
      {
      rhsDuct = createSinkStage( (Tap) element );
      }
    else
      throw new IllegalStateException( "unknown element type: " + element.getClass().getName() );

    return rhsDuct;
    }

  protected SinkStage createSinkStage( Tap element )
    {
    return new SinkStage( flowProcess, (Tap) element );
    }

  protected abstract Gate createCoGroupGate( Group element );

  protected abstract Gate createGroupByGate( Group element );

  protected Duct findExisting( Duct current )
    {
    Collection<Duct> allDucts = getAllDucts();

    for( Duct duct : allDucts )
      {
      if( duct.equals( current ) )
        return duct;
      }

    return current;
    }

  protected void setTraps()
    {
    Collection<Duct> ducts = getAllDucts();

    for( Duct duct : ducts )
      {
      if( !( duct instanceof ElementDuct ) )
        continue;

      ElementDuct elementDuct = (ElementDuct) duct;
      FlowElement flowElement = elementDuct.getFlowElement();

      Set<String> branchNames = new TreeSet<String>();

      if( flowElement instanceof Pipe )
        branchNames.add( ( (Pipe) flowElement ).getName() );
      else if( flowElement instanceof Tap )
        branchNames.addAll( getTapBranchNamesFor( duct ) );
      else
        throw new IllegalStateException( "unexpected duct type" + duct.getClass().getCanonicalName() );

      elementDuct.setBranchNames( branchNames );

      for( String branchName : branchNames )
        {
        Tap trap = step.getTrap( branchName );

        if( trap != null )
          {
          elementDuct.setTrapHandler( new TrapHandler( flowProcess, trap, branchName ) );
          break;
          }
        }

      if( !elementDuct.hasTrapHandler() )
        elementDuct.setTrapHandler( new TrapHandler( flowProcess ) );
      }
    }

  /**
   * Returns a Set as a given tap may be bound to multiple branches
   *
   * @param duct
   * @return
   */
  private Set<String> getTapBranchNamesFor( Duct duct )
    {
    if( duct instanceof SourceStage )
      return step.getSourceName( (Tap) ( (SourceStage) duct ).getFlowElement() );
    else if( duct instanceof SinkStage )
      return step.getSinkName( (Tap) ( (SinkStage) duct ).getFlowElement() );
    else
      throw new IllegalStateException( "duct does not wrap a Tap: " + duct.getClass().getCanonicalName() );
    }

  protected void setScopes()
    {
    Collection<Duct> ducts = getAllDucts();

    for( Duct duct : ducts )
      {
      if( !( duct instanceof ElementDuct ) )
        continue;

      ElementDuct elementDuct = (ElementDuct) duct;

      elementDuct.getIncomingScopes().addAll( step.getPreviousScopes( elementDuct.getFlowElement() ) );
      elementDuct.getOutgoingScopes().addAll( step.getNextScopes( elementDuct.getFlowElement() ) );
      }
    }
  }