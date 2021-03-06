/**
 * Copyright 2014 Microsoft Open Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.microsoftopentechnologies.intellij.components;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.ui.treeStructure.Tree;
import com.microsoftopentechnologies.intellij.forms.*;
import com.microsoftopentechnologies.intellij.helpers.AzureCmdException;
import com.microsoftopentechnologies.intellij.helpers.AzureRestAPIManager;
import com.microsoftopentechnologies.intellij.helpers.UIHelper;
import com.microsoftopentechnologies.intellij.model.*;
import com.sun.deploy.panel.JavaPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class ServerExplorerToolWindowFactory implements ToolWindowFactory {
    private boolean isRefreshEnabled;
    private JPanel treePanel;
    private JLabel loading;

    @Override
    public void createToolWindowContent(final Project project, final ToolWindow toolWindow) {
        isRefreshEnabled = true;

        treePanel = new JPanel(new GridBagLayout());
        loading = new JLabel("Loading services...");

        final JComponent toolWindowComponent = toolWindow.getComponent();

        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        final boolean enabled = Boolean.parseBoolean(pc.getValue("pluginenabled"));
        if(enabled) {

            final Tree tree = new Tree();

            tree.addMouseListener(new MouseListener() {
                @Override
                public void mouseClicked(MouseEvent mouseEvent) {}

                @Override
                public void mousePressed(MouseEvent mouseEvent) {
                    int selRow = tree.getRowForLocation(mouseEvent.getX(), mouseEvent.getY());
                    TreePath selPath = tree.getPathForLocation(mouseEvent.getX(), mouseEvent.getY());

                    if(selPath != null) {
                        if (selRow != -1 && SwingUtilities.isLeftMouseButton(mouseEvent)) {
                            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selPath.getLastPathComponent();

                            if(selectedNode != null) {
                                loadServiceTree(project, tree, selectedNode);
                            }

                        }

                        if(SwingUtilities.isRightMouseButton(mouseEvent) || mouseEvent.isPopupTrigger()) {
                            if (selRow != -1) {
                                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selPath.getLastPathComponent();

                                if (selectedNode != null) {

                                    if(selectedNode.getUserObject() instanceof Subscription) {

                                        JBPopupMenu menu = new JBPopupMenu();
                                        JMenuItem mi = new JMenuItem("Create Service");
                                        mi.addActionListener(new ActionListener() {
                                            @Override
                                            public void actionPerformed(ActionEvent actionEvent) {
                                                CreateNewServiceForm form = new CreateNewServiceForm();
                                                form.setServiceCreated(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        ApplicationManager.getApplication().invokeLater(new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                loadTree(project, tree);
                                                            }
                                                        });
                                                    }
                                                });

                                                form.setModal(true);
                                                UIHelper.packAndCenterJDialog(form);
                                                form.setVisible(true);
                                            }
                                        });
                                        mi.setIconTextGap(16);
                                        menu.add(mi);

                                        menu.show(mouseEvent.getComponent(), mouseEvent.getX(), mouseEvent.getY());
                                    }
                                    else if(selectedNode.getUserObject() instanceof MobileServiceTreeItem) {
                                        MobileServiceTreeItem selectedObject =  (MobileServiceTreeItem) selectedNode.getUserObject();

                                        JBPopupMenu menu = new JBPopupMenu();
                                        for(JBMenuItem mi : getMenuItems(project, selectedObject, selectedNode, tree)) {
                                            mi.setIconTextGap(16);
                                            menu.add(mi);
                                        }

                                        menu.show(mouseEvent.getComponent(), mouseEvent.getX(), mouseEvent.getY());
                                    }
                                }

                            }
                        }
                    }
                }

                @Override
                public void mouseReleased(MouseEvent mouseEvent) {}
                @Override
                public void mouseEntered(MouseEvent mouseEvent) {}
                @Override
                public void mouseExited(MouseEvent mouseEvent) {}
            });

            tree.setCellRenderer(UIHelper.getTreeNodeRenderer());
            tree.setRootVisible(false);

             if(toolWindow instanceof ToolWindowEx) {
                 ToolWindowEx toolWindowEx = (ToolWindowEx) toolWindow;

                 toolWindowEx.setTitleActions(
                     new AnAction("Refresh", "Refresh Service List", UIHelper.loadIcon("refresh.png")) {
                         @Override
                         public void actionPerformed(AnActionEvent event) {
                             loadTree(project, tree);
                         }
                     },
                     new AnAction("Manage Subscriptions", "Manage Subscriptions", AllIcons.Ide.Link) {
                         @Override
                         public void actionPerformed(AnActionEvent anActionEvent) {
                             ManageSubscriptionForm form = new ManageSubscriptionForm(anActionEvent.getProject());
                             UIHelper.packAndCenterJDialog(form);
                             form.setVisible(true);
                             loadTree(project, tree);
                         }
                     });

             }

            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.weighty = 1;
            c.fill = GridBagConstraints.BOTH;
            c.anchor = GridBagConstraints.NORTHWEST;

            treePanel.add(tree,c);

            c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 1;
            c.weightx = 0;
            c.weighty = 0;
            c.fill =  GridBagConstraints.BOTH;
            c.anchor = GridBagConstraints.CENTER;
            treePanel.add(loading,c);

            tree.setVisible(false);
            loading.setVisible(false);

            toolWindowComponent.add(treePanel);

            loadTree(project, tree);
        }
    }

    private void loadServiceTree(Project project, JTree tree, DefaultMutableTreeNode selectedNode) {
        Object[] userObjectPath = selectedNode.getUserObjectPath();

        if(userObjectPath.length > 2) {
            Subscription subscription = (Subscription) userObjectPath[1];
            Service service = (Service) userObjectPath[2];

            UIHelper.treeClick(tree, selectedNode, subscription.getId(), service.getName(), project);
        }
    }



    private void loadTree(Project project, final JTree tree) {
        final ServerExplorerToolWindowFactory toolWindowFactory = this;

        if(isRefreshEnabled) {
            toolWindowFactory.isRefreshEnabled = false;

            final DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
            final DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            root.removeAllChildren();
            model.reload(root);

            tree.setVisible(false);
            loading.setVisible(true);

            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading Mobile Services data...", false) {
                @Override
                public void run(@NotNull ProgressIndicator progressIndicator) {
                    try {
                        progressIndicator.setIndeterminate(true);

                        final ArrayList<Subscription> subscriptionList = AzureRestAPIManager.getManager().getSubscriptionList();
                        final HashMap<String, List<Service>> services = new HashMap<String, List<Service>>();

                        if(subscriptionList == null) {
                            toolWindowFactory.isRefreshEnabled = true;

                            ApplicationManager.getApplication().invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    loading.setVisible(false);
                                    tree.setVisible(true);
                                }
                            });
                        } else {
                            for (Subscription subscription : subscriptionList) {
                                services.put(subscription.getId().toString(), AzureRestAPIManager.getManager().getServiceList(subscription.getId()));
                            }

                            ApplicationManager.getApplication().invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    loading.setVisible(false);
                                    tree.setVisible(true);

                                    for (Subscription subscription : subscriptionList) {

                                        DefaultMutableTreeNode subscriptionNode = new DefaultMutableTreeNode(subscription.getName());
                                        subscriptionNode.setUserObject(subscription);
                                        root.add(subscriptionNode);
                                        model.reload(root);

                                        for (Service service : services.get(subscription.getId().toString())) {

                                            DefaultMutableTreeNode serviceTree = new DefaultMutableTreeNode(service.getName());
                                            serviceTree.setUserObject(service);
                                            subscriptionNode.add(serviceTree);
                                            model.reload(subscriptionNode);
                                        }
                                    }
                                    toolWindowFactory.isRefreshEnabled = true;
                                }
                            });
                        }
                    } catch (AzureCmdException e) {
                        UIHelper.showException("Error querying mobile services data", e);
                    }
                }
            });
        }
    }

    private JBMenuItem[] getMenuItems(final Project p, final MobileServiceTreeItem selectedItem, final DefaultMutableTreeNode selectedNode, final JTree tree) {

        Object[] userObjectPath = selectedNode.getUserObjectPath();

        Subscription subscription = (Subscription) userObjectPath[1];
        Service service = (Service) userObjectPath[2];

        final String serviceName = service.getName();
        final String subscriptionId = subscription.getId().toString();

        if(selectedItem instanceof Service) {
            JBMenuItem newTableMenuItem = new JBMenuItem("Create table");
            JBMenuItem newAPIMenuItem = new JBMenuItem("Create API");
            JBMenuItem newJobMenuItem = new JBMenuItem("Create new job");
            JBMenuItem showLog = new JBMenuItem("Show log");
            JBMenuItem refresh = new JBMenuItem("Refresh");

            final Service item = (Service) selectedItem;

            newTableMenuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    TableForm form = new TableForm();
                    form.setServiceName(item.getName());
                    form.setSubscriptionId(item.getSubcriptionId());
                    form.setProject(p);

                    form.setAfterSave(new Runnable() {
                        @Override
                        public void run() {
                            selectedNode.removeAllChildren();
                            loadServiceTree(p, tree, selectedNode);
                        }
                    });

                    UIHelper.packAndCenterJDialog(form);
                    form.setVisible(true);
                }
            });

            newAPIMenuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    CustomAPIForm form = new CustomAPIForm();
                    form.setServiceName(item.getName());
                    form.setSubscriptionId(item.getSubcriptionId());
                    form.setProject(p);

                    form.setAfterSave(new Runnable() {
                        @Override
                        public void run() {
                            selectedNode.removeAllChildren();
                            loadServiceTree(p, tree, selectedNode);
                        }
                    });

                    UIHelper.packAndCenterJDialog(form);
                    form.setVisible(true);
                }
            });

            newJobMenuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    JobForm form = new JobForm();
                    form.setServiceName(item.getName());
                    form.setSubscriptionId(item.getSubcriptionId());
                    form.setProject(p);
                    form.setTitle("Create new Job");

                    form.setAfterSave(new Runnable() {
                        @Override
                        public void run() {
                            selectedNode.removeAllChildren();
                            loadServiceTree(p, tree, selectedNode);
                        }
                    });

                    UIHelper.packAndCenterJDialog(form);
                    form.setVisible(true);
                }
            });

            showLog.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    final ViewLogForm form = new ViewLogForm();

                    ApplicationManager.getApplication().invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            form.queryLog(item.getSubcriptionId(), item.getName());
                        }
                    });

                    UIHelper.packAndCenterJDialog(form);
                    form.setVisible(true);

                }
            });

            refresh.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    selectedNode.removeAllChildren();
                    loadServiceTree(p, tree, selectedNode);
                }
            });

            return new JBMenuItem[]{
                newTableMenuItem,
                newAPIMenuItem,
                newJobMenuItem,
                showLog,
                refresh
            };
        }

        if(selectedItem instanceof Table) {


            JBMenuItem editTable = new JBMenuItem("Edit Table");

            final Table table = (Table) selectedItem;

            editTable.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {

                    ProgressManager.getInstance().run(new Task.Backgroundable(p, "Loading table info", false) {
                        @Override
                        public void run(@NotNull ProgressIndicator progressIndicator) {
                            try {
                                final Table selectedTable = AzureRestAPIManager.getManager().showTableDetails(UUID.fromString(subscriptionId), serviceName, table.getName());

                                ApplicationManager.getApplication().invokeLater(new Runnable() {
                                    @Override
                                    public void run() {
                                        TableForm form = new TableForm();
                                        form.setServiceName(serviceName);
                                        form.setSubscriptionId(UUID.fromString(subscriptionId));
                                        form.setEditingTable(selectedTable);
                                        form.setProject(p);
                                        UIHelper.packAndCenterJDialog(form);
                                        form.setVisible(true);
                                    }
                                });

                            } catch (Throwable ex) {
                                UIHelper.showException("Error creating table", ex);
                            }

                        }
                    });

                }
            });

            return new JBMenuItem[]{ editTable };
        }

        if(selectedItem instanceof Script) {
            JBMenuItem uploadScript = new JBMenuItem("Update script");

            final Script script = (Script) selectedItem;
            uploadScript.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    ApplicationManager.getApplication().runWriteAction(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                UIHelper.saveScript(p, selectedNode, script, serviceName, subscriptionId);
                            } catch (AzureCmdException e) {
                                UIHelper.showException("Error uploading script:", e);
                            }
                        }
                    });
                }
            });

            return new JBMenuItem[]{
                uploadScript
            };
        }


        if(selectedItem instanceof CustomAPI) {
            final CustomAPI customAPI = (CustomAPI) selectedItem;

            JBMenuItem uploadAPI = new JBMenuItem("Update Custom API");
            JBMenuItem editAPI = new JBMenuItem("Edit Custom API");

            uploadAPI.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    try {
                        UIHelper.saveCustomAPI(p, customAPI, serviceName, subscriptionId);
                    } catch (AzureCmdException e) {
                        UIHelper.showException("Error uploading script:", e);
                    }
                }
            });

            editAPI.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    final CustomAPIForm form = new CustomAPIForm();
                    form.setEditingCustomAPI(customAPI);
                    form.setServiceName(serviceName);

                    form.setSubscriptionId(UUID.fromString(subscriptionId));
                    form.setProject(p);
                    form.setAfterSave(new Runnable() {
                        @Override
                        public void run() {
                            selectedNode.setUserObject(form.getEditingCustomAPI());
                        }
                    });
                    UIHelper.packAndCenterJDialog(form);
                    form.setVisible(true);
                }
            });

            return new JBMenuItem[]{
                uploadAPI,
                editAPI,
            };
        }


        if(selectedItem instanceof Job) {
            final Job job = (Job) selectedItem;

            JBMenuItem uploadJob = new JBMenuItem("Update job");
            JBMenuItem editJob = new JBMenuItem("Edit job");

            uploadJob.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    try {
                        UIHelper.saveJob(p, job, serviceName, subscriptionId);
                    } catch (AzureCmdException e) {
                        UIHelper.showException("Error uploading script:", e);
                    }
                }
            });

            editJob.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent actionEvent) {
                    final JobForm form = new JobForm();
                    form.setJob(job);
                    form.setServiceName(serviceName);
                    form.setTitle("Edit job");
                    form.setSubscriptionId(UUID.fromString(subscriptionId));
                    form.setProject(p);
                    form.setAfterSave(new Runnable() {
                        @Override
                        public void run() {
                            selectedNode.setUserObject(form.getEditingJob());
                        }
                    });
                    form.pack();
                    form.setVisible(true);
                }
            });

            return new JBMenuItem[]{
                uploadJob,
                editJob,
            };
        }

        return null;
    }

}
