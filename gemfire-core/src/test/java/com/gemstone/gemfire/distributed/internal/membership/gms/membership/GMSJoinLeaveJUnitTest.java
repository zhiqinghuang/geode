package com.gemstone.gemfire.distributed.internal.membership.gms.membership;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.distributed.internal.membership.NetView;
import com.gemstone.gemfire.distributed.internal.membership.gms.GMSMember;
import com.gemstone.gemfire.distributed.internal.membership.gms.ServiceConfig;
import com.gemstone.gemfire.distributed.internal.membership.gms.Services;
import com.gemstone.gemfire.distributed.internal.membership.gms.Services.Stopper;
import com.gemstone.gemfire.distributed.internal.membership.gms.interfaces.Authenticator;
import com.gemstone.gemfire.distributed.internal.membership.gms.interfaces.Manager;
import com.gemstone.gemfire.distributed.internal.membership.gms.interfaces.Messenger;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.InstallViewMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.JoinRequestMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.JoinResponseMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.LeaveRequestMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.RemoveMemberMessage;
import com.gemstone.gemfire.distributed.internal.membership.gms.messages.ViewAckMessage;
import com.gemstone.gemfire.internal.Version;
import com.gemstone.gemfire.security.AuthenticationFailedException;
import com.gemstone.gemfire.test.junit.categories.UnitTest;

@Category(UnitTest.class)
public class GMSJoinLeaveJUnitTest {
  private Services services;
  private ServiceConfig mockConfig;
  private DistributionConfig mockDistConfig;
  private Authenticator authenticator;
  private InternalDistributedMember gmsJoinLeaveMemberId;
  private InternalDistributedMember[] mockMembers;
  private InternalDistributedMember mockOldMember;
  private Object credentials = new Object();
  private Messenger messenger;
  private GMSJoinLeave gmsJoinLeave;
  private Manager manager;
  private Stopper stopper;
  
  public void initMocks() throws IOException {
    initMocks(false);
  }
  
  public void initMocks(boolean enableNetworkPartition) throws UnknownHostException {
    mockDistConfig = mock(DistributionConfig.class);
    when(mockDistConfig.getEnableNetworkPartitionDetection()).thenReturn(enableNetworkPartition);
    when(mockDistConfig.getLocators()).thenReturn("localhost[8888]");
    mockConfig = mock(ServiceConfig.class);
    when(mockConfig.getDistributionConfig()).thenReturn(mockDistConfig);
    when(mockDistConfig.getLocators()).thenReturn("localhost[12345]");
    when(mockDistConfig.getMcastPort()).thenReturn(0);
    
    authenticator = mock(Authenticator.class);
    gmsJoinLeaveMemberId = new InternalDistributedMember("localhost", 8887);
    
    messenger = mock(Messenger.class);
    when(messenger.getMemberID()).thenReturn(gmsJoinLeaveMemberId);

    stopper = mock(Stopper.class);
    when(stopper.isCancelInProgress()).thenReturn(false);
    
    manager = mock(Manager.class);
    
    services = mock(Services.class);
    when(services.getAuthenticator()).thenReturn(authenticator);
    when(services.getConfig()).thenReturn(mockConfig);
    when(services.getMessenger()).thenReturn(messenger);
    when(services.getCancelCriterion()).thenReturn(stopper);
    when(services.getManager()).thenReturn(manager);
    
    mockMembers = new InternalDistributedMember[4];
    for (int i = 0; i < mockMembers.length; i++) {
      mockMembers[i] = new InternalDistributedMember("localhost", 8888 + i);
    }
    mockOldMember = new InternalDistributedMember("localhost", 8700, Version.GFE_56);

    gmsJoinLeave = new GMSJoinLeave();
    gmsJoinLeave.init(services);
    gmsJoinLeave.start();
    gmsJoinLeave.started();
  }
  
  @Test
  public void testProcessJoinMessageRejectOldMemberVersion() throws IOException {
    initMocks();
 
    gmsJoinLeave.processMessage(new JoinRequestMessage(mockOldMember, mockOldMember, null));
    Assert.assertTrue("JoinRequest should not have been added to view request", gmsJoinLeave.getViewRequests().size() == 0);
    verify(messenger).send(any(JoinResponseMessage.class));
  }
  
  @Test
  public void testProcessJoinMessageWithBadAuthentication() throws IOException {
    initMocks();
    when(services.getAuthenticator()).thenReturn(authenticator);
    when(authenticator.authenticate(mockMembers[0], credentials)).thenThrow(new AuthenticationFailedException("we want to fail auth here"));
    when(services.getMessenger()).thenReturn(messenger);
         
    gmsJoinLeave.processMessage(new JoinRequestMessage(mockMembers[0], mockMembers[0], credentials));
    Assert.assertTrue("JoinRequest should not have been added to view request", gmsJoinLeave.getViewRequests().size() == 0);
    verify(messenger).send(any(JoinResponseMessage.class));
  }
  
  @Test
  public void testProcessJoinMessageWithAuthenticationButNullCredentials() throws IOException {
    initMocks();
    when(services.getAuthenticator()).thenReturn(authenticator);
    when(authenticator.authenticate(mockMembers[0], null)).thenThrow(new AuthenticationFailedException("we want to fail auth here"));
    when(services.getMessenger()).thenReturn(messenger);
      
    gmsJoinLeave.processMessage(new JoinRequestMessage(mockMembers[0], mockMembers[0], null));
    Assert.assertTrue("JoinRequest should not have been added to view request", gmsJoinLeave.getViewRequests().size() == 0);
    verify(messenger).send(any(JoinResponseMessage.class));
  }
  
  
  private void prepareView() throws IOException {
    int viewId = 1;
    List<InternalDistributedMember> mbrs = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> shutdowns = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> crashes = new LinkedList<InternalDistributedMember>();
    mbrs.add(mockMembers[0]);
    
    when(services.getMessenger()).thenReturn(messenger);
    
    //prepare the view
    NetView netView = new NetView(mockMembers[0], viewId, mbrs, shutdowns, crashes);
    InstallViewMessage installViewMessage = new InstallViewMessage(netView, credentials, true);
    gmsJoinLeave.processMessage(installViewMessage);
    verify(messenger).send(any(ViewAckMessage.class));
  }
  
  private void prepareAndInstallView() throws IOException {
    int viewId = 1;
    List<InternalDistributedMember> mbrs = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> shutdowns = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> crashes = new LinkedList<InternalDistributedMember>();
    mbrs.add(mockMembers[0]);
    
    when(services.getMessenger()).thenReturn(messenger);
    
    //prepare the view
    NetView netView = new NetView(mockMembers[0], viewId, mbrs, shutdowns, crashes);
    InstallViewMessage installViewMessage = new InstallViewMessage(netView, credentials, true);
    gmsJoinLeave.processMessage(installViewMessage);
    verify(messenger).send(any(ViewAckMessage.class));
    
    //install the view
    installViewMessage = new InstallViewMessage(netView, credentials, false);
    gmsJoinLeave.processMessage(installViewMessage);
    Assert.assertEquals(netView, gmsJoinLeave.getView());
  }
  
  @Test
  public void testRemoveMember() throws Exception {
    initMocks();
    prepareAndInstallView();
    MethodExecuted removeMessageSent = new MethodExecuted();
    when(messenger.send(any(RemoveMemberMessage.class))).thenAnswer(removeMessageSent);
    gmsJoinLeave.remove(mockMembers[1], "removing for test");
    assert removeMessageSent.methodExecuted;
  }
  
  
   @Test 
  public void testRejectOlderView() throws IOException {
    initMocks();
    prepareAndInstallView();
    
    List<InternalDistributedMember> mbrs = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> shutdowns = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> crashes = new LinkedList<InternalDistributedMember>();
    mbrs.add(mockMembers[0]);
    mbrs.add(mockMembers[1]);
  
    //try to install an older view where viewId < currentView.viewId
    NetView olderNetView = new NetView(mockMembers[0], 0, mbrs, shutdowns, crashes);
    InstallViewMessage installViewMessage = new InstallViewMessage(olderNetView, credentials, false);
    gmsJoinLeave.processMessage(installViewMessage);
    Assert.assertNotEquals(gmsJoinLeave.getView(), olderNetView);
  }
  
  @Test 
  public void testForceDisconnectedFromNewView() throws IOException {
    initMocks(true);//enabledNetworkPartition;
    Manager mockManager = mock(Manager.class);
    when(services.getManager()).thenReturn(mockManager);
    prepareAndInstallView();

    int viewId = 2;
    List<InternalDistributedMember> mbrs = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> shutdowns = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> crashes = new LinkedList<InternalDistributedMember>();
    mbrs.add(mockMembers[1]);
    mbrs.add(mockMembers[2]);
    mbrs.add(mockMembers[3]);
   
    //install the view
    NetView netView = new NetView(mockMembers[0], viewId, mbrs, shutdowns, crashes);
    InstallViewMessage installViewMessage = new InstallViewMessage(netView, credentials, false);
    gmsJoinLeave.processMessage(installViewMessage);
    
    Assert.assertNotEquals(netView, gmsJoinLeave.getView());
    verify(mockManager).forceDisconnect(any(String.class));
  }
  
  private class MethodExecuted implements Answer {
    private boolean methodExecuted = false;
    @Override
    public Object answer(InvocationOnMock invocation) {
      //do we only expect a join response on a failure?
      methodExecuted = true;
      return null;
    }
    
    public boolean isMethodExecuted() {
      return methodExecuted;
    }
  } 

  @Test
  public void testNonMemberCantRemoveMember() throws Exception {
    String reason = "testing";
    initMocks();
    prepareAndInstallView();
    // test that a non-member can't remove another member
    RemoveMemberMessage msg = new RemoveMemberMessage(mockMembers[0], mockMembers[1], reason);
    msg.setSender(new InternalDistributedMember("localhost", 9000));
    gmsJoinLeave.processMessage(msg);
    Assert.assertTrue("RemoveMemberMessage should not have been added to view requests", gmsJoinLeave.getViewRequests().size() == 0);
  }
  
  @Test
  public void testDuplicateLeaveRequestDoesNotCauseNewView() throws Exception {
    String reason = "testing";
    initMocks();
    prepareAndInstallView();
    gmsJoinLeave.getView().add(gmsJoinLeaveMemberId);
    gmsJoinLeave.becomeCoordinator();

    LeaveRequestMessage msg = new LeaveRequestMessage(gmsJoinLeave.getMemberID(), mockMembers[0], reason);
    msg.setSender(mockMembers[0]);
    gmsJoinLeave.processMessage(msg);
    msg = new LeaveRequestMessage(gmsJoinLeave.getMemberID(), mockMembers[0], reason);
    msg.setSender(mockMembers[0]);
    gmsJoinLeave.processMessage(msg);
    
    waitForViewAndNoRequestsInProgress(7);

    NetView view = gmsJoinLeave.getView();
    Assert.assertTrue("expected member to be removed: " + mockMembers[0] + "; view: " + view,
        !view.contains(mockMembers[0]));
    Assert.assertTrue("expected member to be in shutdownMembers collection: " + mockMembers[0] + "; view: " + view,
        view.getShutdownMembers().contains(mockMembers[0]));
  }
  
  @Test
  public void testDuplicateRemoveRequestDoesNotCauseNewView() throws Exception {
    String reason = "testing";
    initMocks();
    prepareAndInstallView();
    gmsJoinLeave.getView().add(gmsJoinLeaveMemberId);
    gmsJoinLeave.getView().add(mockMembers[1]);
    gmsJoinLeave.becomeCoordinator();
    RemoveMemberMessage msg = new RemoveMemberMessage(gmsJoinLeave.getMemberID(), mockMembers[0], reason);
    msg.setSender(mockMembers[0]);
    gmsJoinLeave.processMessage(msg);
    msg = new RemoveMemberMessage(gmsJoinLeave.getMemberID(), mockMembers[0], reason);
    msg.setSender(mockMembers[0]);
    gmsJoinLeave.processMessage(msg);
    
    waitForViewAndNoRequestsInProgress(7);
    
    NetView view = gmsJoinLeave.getView();
    Assert.assertTrue("expected member to be removed: " + mockMembers[0] + "; view: " + view,
        !view.contains(mockMembers[0]));
    Assert.assertTrue("expected member to be in crashedMembers collection: " + mockMembers[0] + "; view: " + view,
        view.getCrashedMembers().contains(mockMembers[0]));
  }
  
  
  @Test
  public void testDuplicateJoinRequestDoesNotCauseNewView() throws Exception {
    initMocks();
    prepareAndInstallView();
    gmsJoinLeave.getView().add(gmsJoinLeaveMemberId);
    gmsJoinLeave.getView().add(mockMembers[1]);
    gmsJoinLeave.becomeCoordinator();
    JoinRequestMessage msg = new JoinRequestMessage(gmsJoinLeaveMemberId, mockMembers[2], null);
    msg.setSender(mockMembers[2]);
    gmsJoinLeave.processMessage(msg);
    msg = new JoinRequestMessage(gmsJoinLeaveMemberId, mockMembers[2], null);
    msg.setSender(mockMembers[2]);
    gmsJoinLeave.processMessage(msg);
    
    waitForViewAndNoRequestsInProgress(7);
    
    NetView view = gmsJoinLeave.getView();
    Assert.assertTrue("expected member to be added: " + mockMembers[2] + "; view: " + view,
        view.contains(mockMembers[2]));
    List<InternalDistributedMember> members = view.getMembers();
    int occurrences = 0;
    for (InternalDistributedMember mbr: members) {
      if (mbr.equals(mockMembers[2])) {
        occurrences += 1;
      }
    }
    Assert.assertTrue("expected member to only be in the view once: " + mockMembers[2] + "; view: " + view,
        occurrences == 1);
  }
  
  
  private void waitForViewAndNoRequestsInProgress(int viewId) throws InterruptedException {
    // wait for the view processing thread to collect and process the requests
    int sleeps = 0;
    while( !gmsJoinLeave.isStopping() && !gmsJoinLeave.getViewCreator().isWaiting()
        && (!gmsJoinLeave.getViewRequests().isEmpty() || gmsJoinLeave.getView().getViewId() != viewId) ) {
      if (sleeps++ > 20) {
        System.out.println("view requests: " + gmsJoinLeave.getViewRequests());
        System.out.println("current view: " + gmsJoinLeave.getView());
        throw new RuntimeException("timeout waiting for view #" + viewId);
      }
      Thread.sleep(1000);
    }
  }
  
  @Test
  public void testRemoveCausesForcedDisconnect() throws Exception {
    String reason = "testing";
    initMocks();
    prepareAndInstallView();
    gmsJoinLeave.getView().add(mockMembers[1]);
    RemoveMemberMessage msg = new RemoveMemberMessage(mockMembers[0], gmsJoinLeave.getMemberID(), reason);
    msg.setSender(mockMembers[1]);
    gmsJoinLeave.processMessage(msg);
    verify(manager).forceDisconnect(reason);
  }
  
  @Test
  public void testLeaveCausesForcedDisconnect() throws Exception {
    String reason = "testing";
    initMocks();
    prepareAndInstallView();
    gmsJoinLeave.getView().add(gmsJoinLeaveMemberId);
    gmsJoinLeave.getView().add(mockMembers[1]);
    LeaveRequestMessage msg = new LeaveRequestMessage(gmsJoinLeave.getMemberID(), gmsJoinLeave.getMemberID(), reason);
    msg.setSender(mockMembers[1]);
    gmsJoinLeave.processMessage(msg);
    verify(manager).forceDisconnect(reason);
  }

  @Test
  public void testLeaveOfNonMemberIsNoOp() throws Exception {
    String reason = "testing";
    initMocks();
    prepareAndInstallView();
    mockMembers[1].setVmViewId(gmsJoinLeave.getView().getViewId()-1);
    LeaveRequestMessage msg = new LeaveRequestMessage(gmsJoinLeave.getMemberID(), mockMembers[1], reason);
    msg.setSender(mockMembers[1]);
    gmsJoinLeave.processMessage(msg);
    Assert.assertTrue("Expected leave request from non-member to be ignored", gmsJoinLeave.getViewRequests().isEmpty());
  }

  @Test
  public void testBecomeCoordinator() throws Exception {
    String reason = "testing";
    initMocks();
    prepareAndInstallView();
    NetView view = gmsJoinLeave.getView();
    view.add(gmsJoinLeaveMemberId);
    InternalDistributedMember creator = view.getCreator();
    LeaveRequestMessage msg = new LeaveRequestMessage(creator, creator, reason);
    msg.setSender(creator);
    gmsJoinLeave.processMessage(msg);
    Assert.assertTrue("Expected becomeCoordinator to be invoked", gmsJoinLeave.isCoordinator());
  }

  @Test 
  public void testNetworkPartitionDetected() throws IOException {
    initMocks(true);
    prepareAndInstallView();
    
    // set up a view with sufficient members, then create a new view
    // where enough weight is lost to cause a network partition
    
    List<InternalDistributedMember> mbrs = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> shutdowns = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> crashes = new LinkedList<InternalDistributedMember>();
    mbrs.add(mockMembers[0]);
    mbrs.add(mockMembers[1]);
    mbrs.add(mockMembers[2]);
    mbrs.add(gmsJoinLeaveMemberId);
    
    ((GMSMember)mockMembers[1].getNetMember()).setMemberWeight((byte)20);
  
    NetView newView = new NetView(mockMembers[0], gmsJoinLeave.getView().getViewId()+1, mbrs, shutdowns, crashes);
    InstallViewMessage installViewMessage = new InstallViewMessage(newView, credentials, false);
    gmsJoinLeave.processMessage(installViewMessage);
    
    crashes = new LinkedList<>(crashes);
    crashes.add(mockMembers[1]);
    crashes.add(mockMembers[2]);
    mbrs = new LinkedList<>(mbrs);
    mbrs.remove(mockMembers[1]);
    mbrs.remove(mockMembers[2]);
    NetView partitionView = new NetView(mockMembers[0], newView.getViewId()+1, mbrs, shutdowns, crashes);
    installViewMessage = new InstallViewMessage(partitionView, credentials, false);
    gmsJoinLeave.processMessage(installViewMessage);
    
    verify(manager).forceDisconnect(any(String.class));
    verify(manager).quorumLost(crashes, newView);
  }
  
  @Test 
  public void testQuorumLossNotificationWithNetworkPartitionDetectionDisabled() throws IOException {
    initMocks(false);
    prepareAndInstallView();
    
    // set up a view with sufficient members, then create a new view
    // where enough weight is lost to cause a network partition
    
    List<InternalDistributedMember> mbrs = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> shutdowns = new LinkedList<InternalDistributedMember>();
    List<InternalDistributedMember> crashes = new LinkedList<InternalDistributedMember>();
    mbrs.add(mockMembers[0]);
    mbrs.add(mockMembers[1]);
    mbrs.add(mockMembers[2]);
    mbrs.add(gmsJoinLeaveMemberId);
    
    ((GMSMember)mockMembers[1].getNetMember()).setMemberWeight((byte)20);
  
    NetView newView = new NetView(mockMembers[0], gmsJoinLeave.getView().getViewId()+1, mbrs, shutdowns, crashes);
    InstallViewMessage installViewMessage = new InstallViewMessage(newView, credentials, false);
    gmsJoinLeave.processMessage(installViewMessage);
    
    crashes = new LinkedList<>(crashes);
    crashes.add(mockMembers[1]);
    crashes.add(mockMembers[2]);
    mbrs = new LinkedList<>(mbrs);
    mbrs.remove(mockMembers[1]);
    mbrs.remove(mockMembers[2]);
    NetView partitionView = new NetView(mockMembers[0], newView.getViewId()+1, mbrs, shutdowns, crashes);
    installViewMessage = new InstallViewMessage(partitionView, credentials, false);
    gmsJoinLeave.processMessage(installViewMessage);
    
    verify(manager, never()).forceDisconnect(any(String.class));
    verify(manager).quorumLost(crashes, newView);
  }
  
  @Test
  public void testConflictingPrepare() throws Exception {
    initMocks(true);
    prepareAndInstallView();
    
    NetView gmsView = gmsJoinLeave.getView();
    NetView newView = new NetView(gmsView, gmsView.getViewId()+6);
    InstallViewMessage msg = new InstallViewMessage(newView, null, true);
    gmsJoinLeave.processMessage(msg);
    
    NetView alternateView = new NetView(gmsView, gmsView.getViewId()+1);
    msg = new InstallViewMessage(alternateView, null, true);
    gmsJoinLeave.processMessage(msg);
    
    Assert.assertTrue(gmsJoinLeave.getPreparedView().equals(newView));
  }
  
  
}

