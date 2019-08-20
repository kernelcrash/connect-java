package cd.connect.aws.rds

import groovy.transform.CompileStatic
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope

/**
 * Created by Richard Vowles on 24/07/18.
 */
@Mojo(name = "rds-copydbsnapshot",
	defaultPhase = LifecyclePhase.NONE,
	requiresProject = false,
	requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
@CompileStatic
class CopyDbSnapshotMojo extends AbstractMojo {
	@Parameter(property = "rds-dbsnapshot.snapshotCopySourceName")
	String snapshotCopySourceName
	@Parameter(property = "rds-dbsnapshot.snapshotCopyDestinationName")
	String snapshotCopyDestinationName
	@Parameter(property = "rds-dbsnapshot.snapshotCopyDestinationRegion")
	String snapshotCopyDestinationRegion
	@Parameter(property = "rds-clone.aws-profile")
	String awsProfile


	@Parameter(property = "rds-dbsnapshot.skip-rds") // this makes no sense as a property, but we can't is it in an execution if we don't have this
	boolean skip

	protected RdsClone rdsClone;

	@Override
	void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			return
		}

		rdsClone = new RdsClone()
		rdsClone.initialize(awsProfile)

		getLog().info("Copy DB Snapshot from ${snapshotCopySourceName} to ${snapshotCopyDestinationRegion} : ${snapshotCopyDestinationName}")
	}

        protected void copySnapshot() throws MojoFailureException {

                if (snapshotCopySourceName && snapshotCopyDestinationName && snapshotCopyDestinationRegion) {
                        //rdsClient.setRegion(Region.getRegion(snapshotCopyDestinationRegion));

                        //CopyDBSnapshotRequest copySnapshot = new CopyDBSnapshotRequest();
                        //copySnapshot.setSourceDBSnapshotIdentifier(snapshotCopySourceName);
                        //copySnapshot.setTargetDBSnapshotIdentifier(snapshotCopyDestinationName);

                        //DBSnapshot dbSnapshot = rdsClient.copyDBSnapshot(copySnapshot);
                        getLog().info("Copying snapshot from ${snapshotCopySourceName} to ${snapshotCopyDestinationRegion} ${snapshotCopyDestinationName}")

                } else {
                        String err = "One of snapshotCopySourceName, snapshotCopyDestinationName or snapshotCopyDestinationRegion is missing";
                        getLog().error(err)
                        throw new MojoFailureException(err)
                }


        }


}
