package com.orientechnologies.orient.server.distributed.impl.task.transaction;

import com.orientechnologies.common.log.OLogManager;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class OTransactionSequenceManager {

  private volatile long[] sequentials;
  private volatile Long[] promisedSequential;

  public OTransactionSequenceManager() {
    //TODO: make configurable
    this.sequentials = new long[1000];
    this.promisedSequential = new Long[1000];
  }

  public void fill(byte[] data) {
    DataInput dataInput = new DataInputStream(new ByteArrayInputStream(data));
    int len = 0;
    try {
      len = dataInput.readInt();

      long[] newSequential = new long[len];
      for (int i = 0; i < len; i++) {
        newSequential[i] = dataInput.readLong();
      }
      this.sequentials = newSequential;
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error in deserialization", e);
    }
  }

  public synchronized byte[] store() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutput dataOutput = new DataOutputStream(buffer);
    try {
      dataOutput.writeInt(this.sequentials.length);
      for (int i = 0; i < this.sequentials.length; i++) {
        dataOutput.writeLong(this.sequentials[i]);
      }
    } catch (IOException e) {
      OLogManager.instance().error(this, "Error in serialization", e);
    }
    return buffer.toByteArray();
  }

  public synchronized OTransactionId next() {
    int pos;
    do {
      pos = new Random().nextInt(1000);
    } while (this.promisedSequential[pos] != null);
    this.promisedSequential[pos] = this.sequentials[pos] + 1;
    long sequence = this.promisedSequential[pos];
    return new OTransactionId(pos, sequence);
  }

  public synchronized List<OTransactionId> notifySuccess(OTransactionId transactionId) {
    if (this.promisedSequential[transactionId.getPosition()] != null) {
      if (this.promisedSequential[transactionId.getPosition()] == transactionId.getSequence()) {
        this.sequentials[transactionId.getPosition()] = transactionId.getSequence();
        this.promisedSequential[transactionId.getPosition()] = null;
      } else {
        List<OTransactionId> missing = new ArrayList<>();
        for (long x = this.promisedSequential[transactionId.getPosition()].longValue(); x < transactionId.getSequence(); x++) {
          missing.add(new OTransactionId(transactionId.getPosition(), x));
        }
        return missing;
      }
    } else {
      if (this.sequentials[transactionId.getPosition()] + 1 == transactionId.getSequence()) {
        // Not promised but valid, accept it
        //TODO: may need to return this information somehow
        this.sequentials[transactionId.getPosition()] = transactionId.getSequence();
      } else {
        List<OTransactionId> missing = new ArrayList<>();
        for (long x = this.sequentials[transactionId.getPosition()]; x < transactionId.getSequence(); x++) {
          missing.add(new OTransactionId(transactionId.getPosition(), x));
        }
        return missing;
      }
    }
    return null;
  }

  public synchronized boolean validateTransactionId(OTransactionId transactionId) {
    if (this.promisedSequential[transactionId.getPosition()] == null) {
      this.promisedSequential[transactionId.getPosition()] = transactionId.getSequence();
      return true;
    } else {
      return false;
    }
  }

  public synchronized List<OTransactionId> otherStatus(long[] status) {
    List<OTransactionId> missing = null;
    for (int i = 0; i < status.length; i++) {
      if (this.sequentials[i] < status[i]) {
        if (this.promisedSequential[i] == null) {
          if (missing == null) {
            missing = new ArrayList<>();
          }
          for (long x = this.sequentials[i]; x < status[i]; x++) {
            missing.add(new OTransactionId(i, x));
          }
        } else if (this.promisedSequential[i].longValue() != status[i]) {
          if (missing == null) {
            missing = new ArrayList<>();
          }
          for (long x = this.promisedSequential[i].longValue(); x < status[i]; x++) {
            missing.add(new OTransactionId(i, x));
          }
        }
      }
    }
    return missing;
  }
}
