/*
 * Copyright (C) 2011 Alastair R. Beresford
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.nigori.server.appengine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import javax.jdo.JDOHelper;
import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;

import com.google.appengine.api.datastore.Blob;
import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.ShortBlob;
import com.google.nigori.common.Nonce;
import com.google.nigori.common.RevValue;
import com.google.nigori.server.Database;
import com.google.nigori.server.User;
import com.google.nigori.server.UserFactory;
import com.google.nigori.server.UserNotFoundException;

public final class AppEngineDatabase implements Database {

  private static final Logger log = Logger.getLogger(AppEngineDatabase.class.getName());
  protected static final String STORE = "store";

  private static final PersistenceManagerFactory pmfInstance = JDOHelper
      .getPersistenceManagerFactory("transactions-optional");

  private static AEUser getUser(byte[] publicHash, PersistenceManager pm)
      throws JDOObjectNotFoundException {
    return pm.getObjectById(AEUser.class, AEUser.keyForUser(publicHash));
  }

  private static boolean haveUser(byte[] existingUser, PersistenceManager pm) {
    assert pm != null;
    assert existingUser != null;
    try {
      AEUser existing = getUser(existingUser, pm);
      if (existing != null) {
        return true;
      } else {
        return false;
      }
    } catch (JDOObjectNotFoundException e) {
      return false;
    }
  }

  @Override
  public boolean haveUser(byte[] existingUserPK) throws IllegalArgumentException {
    if (existingUserPK == null) {
      throw new IllegalArgumentException("Null existingUser");
    }
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      return haveUser(existingUserPK, pm);
    } finally {
      pm.close();
    }
  }

  @Override
  public boolean addUser(byte[] publicKey, byte[] publicHash) throws IllegalArgumentException {
    AEUser user = new AEUser(publicKey, publicHash, new Date());
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      if (haveUser(publicHash, pm)) {
        log.warning("Did not add user as user already existed");
        return false;
      }
      pm.makePersistent(user);
      return true;
    } finally {
      pm.close();
    }
  }

  @Override
  public boolean deleteUser(User existingUser) {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      AEUser existing = pm.getObjectById(AEUser.class, AEUser.keyForUser(existingUser));
      if (existing != null) {
        pm.deletePersistent(existing);
        return deleteUserData(existing);
      } else {
        return true;
      }
    } catch (JDOObjectNotFoundException e) {
      return false;
    } finally {
      pm.close();
    }
  }

  private boolean deleteUserData(AEUser existing) {
    Collection<byte[]> indices = getIndices(existing);
    boolean success = true;
    for (byte[] index : indices) {
      success &= deleteRecord(existing, index);
    }
    return success;
  }

  @Override
  public byte[] getPublicKey(byte[] publicHash) throws UserNotFoundException {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      return getUser(publicHash, pm).getPublicKey();
    } catch (JDOObjectNotFoundException e) {
      throw new UserNotFoundException();
    } finally {
      pm.close();
    }
  }

  @Override
  public AEUser getUser(byte[] publicHash) throws UserNotFoundException {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      AEUser user = getUser(publicHash, pm);
      return user;
    } catch (JDOObjectNotFoundException e) {
      throw new UserNotFoundException();
    } finally {
      pm.close();
    }
  }

  private static AEUser castUser(User user) {
    if (!(user instanceof AEUser)) {
      user = new AEUser(user.getPublicKey(), user.getPublicHash(), user.getRegistrationDate());
    }
    return (AEUser) user;
  }

  private static Key getLookupKey(User user, byte[] index) {
    return Lookup.makeKey(castUser(user), index);
  }

  @Override
  public Collection<RevValue> getRecord(User user, byte[] index) throws IOException {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {// TODO(drt24): cleanup this method
      Key lookupKey = getLookupKey(user, index);
      // If this doesn't exist there is no key so null gets returned by JDOObjectNotFoundException
      Lookup lookup = pm.getObjectById(Lookup.class, lookupKey);
      List<RevValue> answer = new ArrayList<RevValue>();
      Query getRevisionValues = new Query(AppEngineRecord.class.getSimpleName());
      getRevisionValues.setAncestor(lookupKey);
      DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

      List<Entity> results =
          datastore.prepare(getRevisionValues).asList(FetchOptions.Builder.withDefaults());
      for (Entity result : results) {
        ByteArrayInputStream bais =
            new ByteArrayInputStream(((Blob) result.getProperty("revision")).getBytes());
        ObjectInputStream ndis = new ObjectInputStream(bais);
        answer.add(new RevValue(((Revision) ndis.readObject()).getBytes(),
            ((Blob) result.getProperty("value")).getBytes()));
      }
      if (lookup != null) {
        return answer;
      } else {
        return null;
      }
    } catch (JDOObjectNotFoundException e) {
      return null;
    } catch (ClassNotFoundException e) {
      throw new IOException(e);
    } finally {
      pm.close();
    }
  }

  @Override
  public RevValue getRevision(User user, byte[] index, byte[] revision) throws IOException {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      Key lookupKey = getLookupKey(user, index);
      Key revisionKey = AppEngineRecord.makeKey(lookupKey, new BytesRevision(revision));
      // If this doesn't exist there is no key so null gets returned by JDOObjectNotFoundException
      AppEngineRecord record = pm.getObjectById(AppEngineRecord.class, revisionKey);
      return new RevValue(revision, record.getValue());
    } catch (JDOObjectNotFoundException e) {
      return null;
    } finally {
      pm.close();
    }
  }

  @Override
  public Collection<byte[]> getIndices(User user) {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      Query getIndices = new Query(Lookup.class.getSimpleName());
      getIndices.setAncestor(castUser(user).getKey());
      DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
      List<Entity> results =
          datastore.prepare(getIndices).asList(FetchOptions.Builder.withDefaults());
      List<byte[]> answer = new ArrayList<byte[]>();
      for (Entity result : results) {
        Object index = result.getProperty("index");
        if (index instanceof ShortBlob) {
          answer.add(((ShortBlob) index).getBytes());
        } else if (index instanceof Blob) {
          answer.add(((Blob) index).getBytes());
        } else
          throw new ClassCastException("Could not transform to byte[] via a (Short)Blob: "
              + index.getClass().getSimpleName());
      }
      return answer;
    } catch (JDOObjectNotFoundException e) {
      return null;
    } finally {
      pm.close();
    }
  }

  @Override
  public Collection<byte[]> getRevisions(User user, byte[] index) throws IOException {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {// TODO(drt24): cleanup this method
      // TODO(drt24): we can do this faster with a key only lookup
      Key lookupKey = getLookupKey(user, index);
      // If this doesn't exist there is no key so null gets returned by JDOObjectNotFoundException
      Lookup lookup = pm.getObjectById(Lookup.class, lookupKey);
      List<byte[]> answer = new ArrayList<byte[]>();
      Query getRevisionValues = new Query(AppEngineRecord.class.getSimpleName());
      getRevisionValues.setAncestor(lookupKey);
      DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

      List<Entity> results =
          datastore.prepare(getRevisionValues).asList(FetchOptions.Builder.withDefaults());
      for (Entity result : results) {
        ByteArrayInputStream bais =
            new ByteArrayInputStream(((Blob) result.getProperty("revision")).getBytes());
        ObjectInputStream ndis = new ObjectInputStream(bais);
        answer.add(((Revision) ndis.readObject()).getBytes());
      }
      if (lookup != null) {
        return answer;
      } else {
        return null;
      }
    } catch (JDOObjectNotFoundException e) {
      return null;
    } catch (ClassNotFoundException e) {
      throw new IOException(e);
    } finally {
      pm.close();
    }
  }

  @Override
  public boolean putRecord(User user, byte[] index, byte[] bRevision, byte[] data) {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    Revision revision = new BytesRevision(bRevision);
    try {
      Key lookupKey = getLookupKey(user, index);
      Lookup lookup;
      try {
        lookup = pm.getObjectById(Lookup.class, lookupKey);
      } catch (JDOObjectNotFoundException e) {
        lookup = new Lookup(lookupKey, index);
        pm.makePersistent(lookup);
      }
      // TODO(drt24): Do revisions properly, need to only add if not already existing.
      AppEngineRecord record = new AppEngineRecord(lookup.getKey(), revision, data);
      pm.makePersistent(record);
      return true;
    } finally {
      pm.close();
    }
  }

  @Override
  public boolean deleteRecord(User user, byte[] index) {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      Key lookupKey = getLookupKey(user, index);
      Lookup lookup = pm.getObjectById(Lookup.class, lookupKey);
      // TODO(drt24) multiple revisions
      // TODO(drt24) cleanup
      try {
        Query getRevisionValues = new Query(AppEngineRecord.class.getSimpleName());
        getRevisionValues.setAncestor(lookupKey);
        DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

        List<Entity> results =
            datastore.prepare(getRevisionValues).asList(FetchOptions.Builder.withDefaults());
        for (Entity entity : results) {
          pm.deletePersistent(pm.getObjectById(AppEngineRecord.class, entity.getKey()));
        }

      } finally {// even if there is no value the index still needs to be deleted - but we haven't
                 // actually done a delete
        pm.deletePersistent(lookup);
      }
      return true;
    } catch (JDOObjectNotFoundException e) {
      return false;
    } finally {
      pm.close();
    }
  }

  @Override
  public UserFactory getUserFactory() {
    return AEUser.Factory.getInstance();
  }

  @Override
  public boolean checkAndAddNonce(Nonce nonce, byte[] publicHash) {
    if (!nonce.isRecent()) {
      return false;
    }
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      AENonce aeNonce = new AENonce(nonce, publicHash);
      try {
        pm.getObjectById(AENonce.class, aeNonce.getKey());
        return false;// getObjectById should throw an exception
      } catch (JDOObjectNotFoundException e) {
        // We haven't seen this nonce yet so add it and return true
        pm.makePersistent(aeNonce);
        return true;
      }
    } finally {
      pm.close();
    }
  }

  @Override
  public void clearOldNonces() {
    PersistenceManager pm = pmfInstance.getPersistenceManager();
    try {
      Query getAllNonces = new Query(AENonce.class.getSimpleName());
      DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

      List<Entity> results =
          datastore.prepare(getAllNonces).asList(FetchOptions.Builder.withDefaults());
      for (Entity entity : results) {
        int sinceEpoch = (int) (long) ((Long) entity.getProperty("sinceEpoch"));
        if (!Nonce.isRecent(sinceEpoch)) {
          pm.deletePersistent(pm.getObjectById(AENonce.class, entity.getKey()));
        }
      }

    } finally {
      pm.close();
    }
  }

}