package cd.connect.aws.rds

import com.amazonaws.auth.AWSCredentialsProviderChain
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.rds.AmazonRDS
import com.amazonaws.services.rds.AmazonRDSClientBuilder
import com.amazonaws.services.rds.model.*

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
        AmazonRDS rdsClient;


	@Override
	void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			return
		}


		if (snapshotCopySourceName) {
			getLog().info("snapshotCopySourceName = ${snapshotCopySourceName}")
		}
		if (snapshotCopyDestinationName) {
			getLog().info("snapshotCopyDestinationName = ${snapshotCopyDestinationName}")
		}
		if (snapshotCopyDestinationRegion) {
			getLog().info("snapshotCopyDestinationRegion = ${snapshotCopyDestinationRegion}")
		}


		if (snapshotCopySourceName && snapshotCopyDestinationName && snapshotCopyDestinationRegion) {
			rdsClone = new RdsClone()
			rdsClone.initializeWithRegion(awsProfile,snapshotCopyDestinationRegion)

			rdsClone.copySnapshot(snapshotCopySourceName, snapshotCopyDestinationName)
		} else {
                        String err = "One of snapshotCopySourceName, snapshotCopyDestinationName or snapshotCopyDestinationRegion is missing";
                        getLog().error(err)
                        throw new MojoFailureException(err)
		}
	}


}
