package cd.connect.aws.rds

import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.rds.AmazonRDS
import com.amazonaws.services.rds.AmazonRDSClientBuilder
import com.amazonaws.services.rds.model.*
import com.amazonaws.regions.*

import groovy.transform.CompileStatic
import org.apache.maven.plugin.MojoFailureException

/**
 * Created by Richard Vowles on 16/02/18.
 */
@CompileStatic
class RdsClone {
	AmazonRDS rdsClient;

	void initialize(String profile = null) {
		AWSCredentialsProviderChain chain = new DefaultAWSCredentialsProviderChain()

		if (profile != null) {
			chain = new AWSCredentialsProviderChain(Arrays.asList(new ProfileCredentialsProvider(profile)))
		}

		rdsClient = AmazonRDSClientBuilder.standard().withCredentials(chain).build()
	}

	void initializeWithRegion(String profile = null, String region) {
		AWSCredentialsProviderChain chain = new DefaultAWSCredentialsProviderChain()

		if (profile != null) {
			chain = new AWSCredentialsProviderChain(Arrays.asList(new ProfileCredentialsProvider(profile)))
		}

		rdsClient = AmazonRDSClientBuilder.standard()
			.withCredentials(chain)
			.withRegion(region)
			.build()
	}


	String snapshotDatabase(String database, int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds, String snapshotOverride) {
		String snapshotName = snapshotOverride ?: database + "-" + System.currentTimeMillis()
		long start = System.currentTimeMillis()
		CreateDBSnapshotRequest snap = new CreateDBSnapshotRequest()
			.withDBInstanceIdentifier(database)
			.withDBSnapshotIdentifier(snapshotName)

		rdsClient.createDBSnapshot(snap)

		long end = System.currentTimeMillis()

		println "snapshot ${snapshotName} created in ${(end - start)}ms (${snap}";

		boolean success = waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, { ->
			return "available".equals(snapshotStatus(snapshotName, database))
		});

		return success ? snapshotName : null;
	}

	String snapshotStatus(String snapshot, String database) {
		try {
			DescribeDBSnapshotsResult snapshots = rdsClient.describeDBSnapshots(new DescribeDBSnapshotsRequest()
				.withDBInstanceIdentifier(database)
				.withDBSnapshotIdentifier(snapshot))
			println "snapshots are ${snapshots} for ${snapshot} and db ${database}"
			if (snapshots && snapshots.getDBSnapshots() != null && snapshots.getDBSnapshots().size() > 0) {
				return snapshots.getDBSnapshots().first().getStatus()
			}

			return null
		} catch (Exception e) {
			return null
		}
	}

	List<String> discoverSnapshots(String database, boolean ignoreAutomated = false) {
		DescribeDBSnapshotsResult snapshots = rdsClient.describeDBSnapshots(new DescribeDBSnapshotsRequest()
			.withDBInstanceIdentifier(database)
		)

		return snapshots.DBSnapshots
			.findAll({ (ignoreAutomated && it.getSnapshotType() != 'automated') || !ignoreAutomated })
			.collect({ it.getDBSnapshotIdentifier() })
	}

	void createDatabaseInstanceFromSnapshot(String database, String snapshot, String dbSubnetGroupName,
	                                        List<VpcSecurityGroupMembership> snapshotVpcSecurityGroups,
	                                        List<DBSecurityGroupMembership> dbSecurityGroups,
	                                        int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds,
	                                        List<String> securityGroupNames,
	                                        List<DatabaseTag> tags,
	                                        String password,
	                                        boolean multiAZ,
	                                        CreateInstanceResult completed) {
		try {
			rdsClient.describeDBInstances(new DescribeDBInstancesRequest().withDBInstanceIdentifier(database))

			deleteDatabaseInstance(database, waitPeriodInMinutes, waitPeriodPollTimeInSeconds)
		} catch (DBInstanceNotFoundException dinfe) {
		}

		long end = System.currentTimeMillis()

		long start = System.currentTimeMillis()
		def restoreRequest = new RestoreDBInstanceFromDBSnapshotRequest()
			.withDBInstanceIdentifier(database)
			.withDBSnapshotIdentifier(snapshot)

		if (tags) {
			restoreRequest.withTags(tags.collect({ new Tag().withKey(it.name).withValue(it.value) }))
		}


		if (dbSubnetGroupName) {
			restoreRequest.withDBSubnetGroupName(dbSubnetGroupName)
		}

		restoreRequest.withMultiAZ(multiAZ)

		if (multiAZ) {
			println("restoring as multi-AZ")
		}

		DBInstance instance = rdsClient.restoreDBInstanceFromDBSnapshot(restoreRequest)

		println "creating ${database} from instance ${snapshot}..."

		boolean success = waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, { ->
			return "available".equals(databaseStatus(database))
		})

		println "created ${database} from instance ${snapshot} in ${end - start}ms - success? ${success}"

		if (success) {
			ModifyDBInstanceRequest modifyRequest = null

			if (snapshotVpcSecurityGroups || dbSecurityGroups) {
				println("modifying db: copying security and dbSubnetGroupName groups from snapshot.")
				modifyRequest = new ModifyDBInstanceRequest().withDBInstanceIdentifier(instance.getDBInstanceIdentifier())
				if (snapshotVpcSecurityGroups) {
					modifyRequest.withVpcSecurityGroupIds(snapshotVpcSecurityGroups.collect({ it.vpcSecurityGroupId }))
				}
				if (dbSecurityGroups) {
					modifyRequest.withDBSecurityGroups(dbSecurityGroups.collect({ it.getDBSecurityGroupName() }))
				}
			} else if (securityGroupNames) {
				println("modifying db: with dbSubnetGroupName security group ids ${securityGroupNames}")
				modifyRequest = new ModifyDBInstanceRequest().withDBInstanceIdentifier(instance.getDBInstanceIdentifier())
				modifyRequest.withVpcSecurityGroupIds(securityGroupNames)
			}

			if (password) {
				if (modifyRequest == null) {
					modifyRequest = new ModifyDBInstanceRequest().withDBInstanceIdentifier(instance.getDBInstanceIdentifier())
				}

				modifyRequest.withMasterUserPassword(password)
			}

			if (modifyRequest) {
				modifyRequest.withApplyImmediately(true);
				println "Modify request issued for ${database} / ${snapshot}: ${modifyRequest.toString()}"
				rdsClient.modifyDBInstance(modifyRequest)
				success = waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, { ->
					return "available".equals(databaseStatus(database))
				})
			}
		} else {
			throw new MojoFailureException("Creation of database failed.")
		}

		if (completed) {
			completed.result(success, getDatabaseInstance(database))
		}
	}

	// defaults to success = true if we aren't waiting for it
	protected boolean waitFor(int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds, Closure criteria) {
		boolean success = true;

		int waitPeriodInSeconds = waitPeriodInMinutes * 60

		while (waitPeriodPollTimeInSeconds > 0 && waitPeriodInSeconds > 0) {
			success = criteria()

			if (success) {
				break
			}

			Thread.sleep(waitPeriodPollTimeInSeconds * 1000)

			waitPeriodInSeconds -= waitPeriodPollTimeInSeconds

			println "${waitPeriodInSeconds} seconds left..."
		}

		return success
	}

	void deleteDatabaseInstance(String database, int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds) {
		rdsClient.deleteDBInstance(new DeleteDBInstanceRequest().withDBInstanceIdentifier(database).withSkipFinalSnapshot(true))

		if (waitPeriodInMinutes) {
			println "waiting for deletion"
			waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, {
				// when the database has gone away...
				try {
					rdsClient.describeDBInstances(new DescribeDBInstancesRequest().withDBInstanceIdentifier(database))

					return false
				} catch (DBInstanceNotFoundException nfe) {
					return true
				}
			})
		}
	}

	String databaseStatus(String database) {
		return getDatabaseInstance(database)?.DBInstanceStatus
	}


	DBInstance getDatabaseInstance(String database) {
		try {
			def instances = rdsClient.describeDBInstances(new DescribeDBInstancesRequest().withDBInstanceIdentifier(database))
			println "instances = ${instances}"
			return instances?.DBInstances?.first()
		} catch (Exception e) {
			e.printStackTrace()
			return null
		}
	}

	void deleteDatabaseSnapshot(String snapshot, String database, int waitPeriodInMinutes, int waitPeriodPollTimeInSeconds) {
		rdsClient.deleteDBSnapshot(new DeleteDBSnapshotRequest().withDBSnapshotIdentifier(snapshot))

		if (waitPeriodInMinutes) {
			waitFor(waitPeriodInMinutes, waitPeriodPollTimeInSeconds, { ->
				return snapshotStatus(snapshot, database) == null
			})
		}
	}

	// this allows us to clean unwanted snapshots for this database, particularly the ones created by
	// rds automatically as it creates the database. We don't want for these as it isn't important
	void removeAttachedSnapshots(String databaseName, String exclude = null) {
		discoverSnapshots(databaseName, true).each { String name ->
			if (!exclude || !exclude.equals(name)) {
				println "deleting snapshot ${databaseName}:${name}"
				deleteDatabaseSnapshot(name, databaseName, 0, 0)
			} else {
				println "skipping deleting snapshot ${name}"
			}
		}
	}

	void deleteDatabase(String database) {
		DeleteDBInstanceRequest del = new DeleteDBInstanceRequest().withDBInstanceIdentifier(database)
		  .withSkipFinalSnapshot(true)
		rdsClient.deleteDBInstance(del)
	}

        void copySnapshot(String sourceName, String destinationName) {

                println "Copy DB Snapshot from ${sourceName} to ${destinationName}"
                //rdsClient.setRegion(Region.getRegion(Regions.fromName(destinationRegion)));

                CopyDBSnapshotRequest copySnapshotReq = new CopyDBSnapshotRequest();
                copySnapshotReq.setSourceDBSnapshotIdentifier(sourceName);
                copySnapshotReq.setTargetDBSnapshotIdentifier(destinationName);

                DBSnapshot dbSnapshot = rdsClient.copyDBSnapshot(copySnapshotReq);
                println "Copying snapshot from ${sourceName} to ${destinationName}"

        }

}
