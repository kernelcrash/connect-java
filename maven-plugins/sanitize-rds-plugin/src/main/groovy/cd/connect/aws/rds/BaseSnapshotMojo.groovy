package cd.connect.aws.rds

import com.amazonaws.services.rds.model.DBInstance
import com.amazonaws.services.rds.model.DBSecurityGroupMembership
import com.amazonaws.services.rds.model.VpcSecurityGroupMembership
import groovy.transform.CompileStatic
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject

@CompileStatic
abstract public class BaseSnapshotMojo extends AbstractMojo {

	@Parameter(property = "rds-clone.database", required = true)
	String database
	@Parameter(property = "rds-clone.username")
	String username
	@Parameter(property = "rds-clone.password")
	String password
	@Parameter(property = "rds-clone.snapshot-wait")
	int snapshotWaitInMinutes = 25
	@Parameter(property = "rds-clone.restore-wait")
	int restoreWaitInMinutes = 25
	@Parameter(property = "rds-clone.poll-interval")
	int pollTimeInSeconds = 25
	@Parameter(property = "rds-clone.snapshotName")
	String snapshotName

	@Parameter(property = "rds-clone.region")
	String region

	@Parameter(property = "rds-clone.aws-profile")
	String awsProfile
	@Parameter(property = 'rds-clone.db-subnet-group-name')
	String dbSubnetGroupName
	@Parameter(property = 'rds-clone.security-group-names')
	List<String> securityGroupNames = []
	@Parameter(property = 'rds-clone.multiAZ')
	boolean multiAZ = false;
	@Parameter(property = 'rds-clone.db-instance-class')
	String dbInstanceClass

	@Parameter(property = "rds-restore.tags")
	List<DatabaseTag> tags

	@Parameter(defaultValue = '${project}', readonly = true)
	MavenProject project;

	protected RdsClone rdsClone;
	protected String realSnapshotName;
	protected List<VpcSecurityGroupMembership> snapshotVpcSecurityGroups
	protected List<DBSecurityGroupMembership> dbSecurityGroups

	protected void init() {
		rdsClone = new RdsClone()
		if (region) {
			rdsClone.initializeWithRegion(awsProfile,region)
		} else {
			rdsClone.initialize(awsProfile)
		}

	}
	protected String snapshot() throws MojoFailureException {
		if (snapshotName) {
			try {
				String status = rdsClone.snapshotStatus(snapshotName, database)
				if (status) {
					getLog().info("Found snapshot with name ${snapshotName} - status ${status}, deleting.")
					rdsClone.deleteDatabaseSnapshot(snapshotName, database, snapshotWaitInMinutes, pollTimeInSeconds)


				}
			} catch (Exception e) {
				getLog().info("Unable to delete snapshot ${snapshotName}: ${e.message}", e)
			}
		}

		DBInstance instance = rdsClone.getDatabaseInstance(database)
		if (!dbSubnetGroupName && instance) {
			dbSubnetGroupName = instance.DBSubnetGroup?.DBSubnetGroupName
			snapshotVpcSecurityGroups = instance.vpcSecurityGroups
			dbSecurityGroups = instance.DBSecurityGroups
		}
		realSnapshotName = rdsClone.snapshotDatabase(database, snapshotWaitInMinutes, pollTimeInSeconds, snapshotName)
		if (!realSnapshotName) {
			throw new MojoFailureException("Unable to create snapshot within timeframe")
		}
	}

	protected void restoreSnapshot(String dbName, String snapshotName, CreateInstanceResult createInstanceResult) {
		if (rdsClone.snapshotStatus(snapshotName, database) == null) {
			String err = "There is no Snapshot called ${snapshotName} for db ${dbName}";
			getLog().error(err)
			throw new MojoFailureException(err)
		}

		getLog().info("Restoring snapshot `${snapshotName}` into database `${dbName}` using vpc subnet `${dbSubnetGroupName?:'default'}")

		rdsClone.createDatabaseInstanceFromSnapshot(dbName, snapshotName, dbSubnetGroupName,
			snapshotVpcSecurityGroups, dbSecurityGroups,
			restoreWaitInMinutes, pollTimeInSeconds, securityGroupNames, tags, password, multiAZ, dbInstanceClass, createInstanceResult)
	}


}

