/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,  
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.impl.client.container;

import java.util.List;
import java.util.concurrent.Callable;

import org.jboss.arquillian.impl.client.container.event.DeployDeployment;
import org.jboss.arquillian.impl.client.container.event.DeployManagedDeployments;
import org.jboss.arquillian.impl.client.container.event.DeploymentEvent;
import org.jboss.arquillian.impl.client.container.event.UnDeployDeployment;
import org.jboss.arquillian.impl.client.container.event.UnDeployManagedDeployments;
import org.jboss.arquillian.impl.domain.Container;
import org.jboss.arquillian.impl.domain.ContainerRegistry;
import org.jboss.arquillian.spi.client.container.DeployableContainer;
import org.jboss.arquillian.spi.client.deployment.Deployment;
import org.jboss.arquillian.spi.client.deployment.DeploymentDescription;
import org.jboss.arquillian.spi.client.deployment.DeploymentScenario;
import org.jboss.arquillian.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.spi.client.test.TargetDescription;
import org.jboss.arquillian.spi.core.Event;
import org.jboss.arquillian.spi.core.Injector;
import org.jboss.arquillian.spi.core.Instance;
import org.jboss.arquillian.spi.core.InstanceProducer;
import org.jboss.arquillian.spi.core.annotation.DeploymentScoped;
import org.jboss.arquillian.spi.core.annotation.Inject;
import org.jboss.arquillian.spi.core.annotation.Observes;
import org.jboss.arquillian.spi.event.container.AfterDeploy;
import org.jboss.arquillian.spi.event.container.AfterUnDeploy;
import org.jboss.arquillian.spi.event.container.BeforeDeploy;
import org.jboss.arquillian.spi.event.container.BeforeUnDeploy;
import org.jboss.arquillian.spi.event.container.DeployerEvent;

/**
 * Controller for handling all Deployment related operations. <br/>
 * <br/> 
 * 
 * Fires DeployDeployment events for each deployment that should be deployed during startup. This so the Cores exception handling
 * will be triggered if Deployment fails inside the context of the deployment and container. This lets extensions listen for Exceptions types
 * and handle them inside the same context.
 *
 * @author <a href="mailto:aslak@redhat.com">Aslak Knutsen</a>
 * @version $Revision: $
 */
public class ContainerDeployController
{
   @Inject
   private Instance<ContainerRegistry> containerRegistry;
   
   @Inject
   private Instance<DeploymentScenario> deploymentScenario;

   @Inject
   private Instance<Injector> injector;

   /**
    * Deploy all deployments marked as startup = true.
    * 
    * @param event
    * @throws Exception
    */
   public void deployManaged(@Observes DeployManagedDeployments event) throws Exception
   {
      forEachManagedDeployment(new Operation<Container, Deployment>()
      {
         @Inject 
         private Event<DeploymentEvent> event;
         
         @Override
         public void perform(Container container, Deployment deployment) throws Exception
         {
            event.fire(new DeployDeployment(container, deployment));            
         }
      });
   }

   /**
    * Undeploy all deployments marked as startup = true. 
    * 
    * @param event
    * @throws Exception
    */
   public void undeployManaged(@Observes UnDeployManagedDeployments event) throws Exception
   {
      forEachManagedDeployment(new Operation<Container, Deployment>()
      {
         @Inject 
         private Event<DeploymentEvent> event;
         
         @Override
         public void perform(Container container, Deployment deployment) throws Exception
         {
            event.fire(new UnDeployDeployment(container, deployment));            
         }
      });
   }

   public void deploy(@Observes final DeployDeployment event) throws Exception
   {
      executeOperation(new Callable<Void>()
      {
         @Inject
         private Event<DeployerEvent> deployEvent;
         
         @Inject @DeploymentScoped
         private InstanceProducer<DeploymentDescription> deploymentDescriptionProducer;

         @Inject @DeploymentScoped
         private InstanceProducer<Deployment> deploymentProducer;
         
         @Inject @DeploymentScoped
         private InstanceProducer<ProtocolMetaData> protocolMetadata;

         @Override
         public Void call() throws Exception
         {
            DeployableContainer<?> deployableContainer = event.getDeployableContainer();
            Deployment deployment = event.getDeployment();
            DeploymentDescription deploymentDescription = deployment.getDescription();
            
            /*
             * TODO: should the DeploymentDescription producer some how be automatically registered ?
             * Or should we just 'know' who is the first one to create the context
             */
            deploymentDescriptionProducer.set(deploymentDescription);
            deploymentProducer.set(deployment);
            
            deployEvent.fire(new BeforeDeploy(deployableContainer, deploymentDescription));

            if(deploymentDescription.isArchiveDeployment())
            {
               protocolMetadata.set(deployableContainer.deploy(
                     deploymentDescription.getTestableArchive() != null ? deploymentDescription.getTestableArchive():deploymentDescription.getArchive()));
            }
            else
            {
               deployableContainer.deploy(deploymentDescription.getDescriptor());
            }
            
            deployEvent.fire(new AfterDeploy(deployableContainer, deploymentDescription));
            return null;
         }
      });
   }
   
   public void undeploy(@Observes final UnDeployDeployment event) throws Exception
   {
      executeOperation(new Callable<Void>()
      {
         @Inject
         private Event<DeployerEvent> deployEvent;

         @Override
         public Void call() throws Exception
         {
            DeployableContainer<?> deployableContainer = event.getDeployableContainer();
            Deployment deployment = event.getDeployment();
            DeploymentDescription description = deployment.getDescription();
            
            deployEvent.fire(new BeforeUnDeploy(deployableContainer, description));

            if(deployment.getDescription().isArchiveDeployment())
            {
               try
               {
                  deployableContainer.undeploy(
                        description.getTestableArchive() != null ? description.getTestableArchive():description.getArchive());
               }
               catch (Exception e) 
               {
                  if(!deployment.hasDeploymentError())
                  {
                     throw e;
                  }
               }
            }
            else
            {
               deployableContainer.undeploy(description.getDescriptor());
            }
            
            deployEvent.fire(new AfterUnDeploy(deployableContainer, description));
            return null;
         }
      });
   }
   
   private void forEachManagedDeployment(Operation<Container, Deployment> operation) throws Exception
   {
      injector.get().inject(operation);
      ContainerRegistry containerRegistry = this.containerRegistry.get();
      DeploymentScenario deploymentScenario = this.deploymentScenario.get();
      
      for(TargetDescription target : deploymentScenario.getTargets())
      {
         List<Deployment> startUpDeployments = deploymentScenario.getStartupDeploymentsFor(target);
         if(startUpDeployments.size() == 0)
         {
            continue; // nothing to do, move on 
         }
         
         // Container should exists, handled by up front validation
         Container container = containerRegistry.getContainer(target);
         
         for(Deployment deployment : startUpDeployments)
         {
            operation.perform(container, deployment);
         }
      }
   }
   
   private void executeOperation(Callable<Void> operation)
      throws Exception
   {
      injector.get().inject(operation);
      operation.call();
   }

   public interface Operation<T, X>
   {
      void perform(T container, X deployment) throws Exception;
   }
}
