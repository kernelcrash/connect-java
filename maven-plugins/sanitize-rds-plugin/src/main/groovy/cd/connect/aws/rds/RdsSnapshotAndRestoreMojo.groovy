
package cd.connect.aws.rds

import com.amazonaws.services.rds.model.DBInstance
import groovy.transform.CompileStatic
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.codehaus.groovy.runtime.ProcessGroovyMethods

/**
 *
 * @author Richard Vowles - https://plus.google.com/+RichardVowles
 */
@Mojo(name = "rds-sanitize",
	defaultPhase = LifecyclePhase.NONE,
	requiresProject = false,
	requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
@CompileStatic
class RdsSnapshotAndRestoreMojo extends BaseSnapshotMojo {
	@Parameter(property = "rds-restore.hostname")
	String hostname
	@Parameter(property = "rds-restore.dumpCommand")
	String dumpCommand;
	@Parameter(property = "rds-restore.dumpFolder")
	String dumpFolder = "dump";
	@Parameter(property = "rds-restore.schemas")
	List<String> schemas;
	@Parameter(property = "rds-restore.executeSqlCommand")
	String executeSqlCommand;
	@Parameter(property = "rds-restore.sanitizeFolder")
	String sanitizeFolder = "src/sql"
	@Parameter(property = "rds-restore.sanitizeSqls")
	List<String> sanitizeSqls;
	@Parameter(property = "rds-restore.snapshot-name-override")
	String snapshotNameOverride
	@Parameter(property = "rds-restore.skip-snapshot")
	boolean skipSnapshot
	@Parameter(property = "rds-restore.skip-restore")
	boolean skipRestore = false
	@Parameter(property = "rds-restore.skip-sanitize")
	boolean skipSanitize = false
	@Parameter(property = "rds-restore.skip-dump")
	boolean skipDump = false
	@Parameter(property = "rds-restore.skip-copy")
	boolean skipCopy = true

	// this one deletes the snapshot that was just made
	@Parameter(property = "rds-restore.clean-sanitize")
	boolean cleanSanitize = false
	// this one removes all snapshots from the restored database
	@Parameter(property = "rds-restore.clean-snapshot")
	boolean cleanSnapshot = false
	@Parameter(property = 'rds-clone.cleanupDatabaseSnapshots')
	boolean cleanupDatabaseSnapshots = false
	@Parameter(property = "rds-restore.parallel-dump")
	boolean parallelDump = false
	@Parameter(property = "rds-restore.into-database")
	String intoDatabase
	@Parameter(property = "rds-restore.skip-rds") // this makes no sense as a property, but we can't is it in an execution if we don't have this
	boolean skip
	@Parameter(property = "rds-restore.dumpPrefix")
	String dumpPrefix
	@Parameter(property = "rds-restore.dumpSuffix")
	String dumpSuffix

	private String createCommand(String base, String msg, String hostName) {
		String cwd = new File('.').absolutePath

		String command = base.replace('$username', username).replace('$hostname', hostName)
		String fakeCommand = command.replace('$password', '*******')
		command = command.replace('$password', password).replace('$cwd', cwd)

		getLog().info("Using command for ${msg}: `${fakeCommand}` with schemas `${schemas}")

		return command
	}

	void checkIntoDatabaseForSecurityGroupAndVpc() {
		if (!dbSubnetGroupName && !securityGroupNames && intoDatabase) {
			try {
				DBInstance instance = rdsClone.getDatabaseInstance(intoDatabase)

				if (instance) {
					dbSubnetGroupName = instance.DBSubnetGroup?.DBSubnetGroupName
					securityGroupNames = instance.vpcSecurityGroups.collect({vpc -> vpc.vpcSecurityGroupId})
				}
			} catch (Exception e) {
				getLog().info("No existing database ${intoDatabase} to copy into, not cloning VPC and security groups.")
			}
		}
	}

	@Override
	void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) { return }


		getLog().info("Skip snapshot ${skipSnapshot}, restore ${skipRestore}, sanitize ${skipSanitize}, snapshotNameOverride `${snapshotNameOverride}`")

		if (skipSnapshot) {
			if (!skipRestore && !snapshotNameOverride) {
				throw new MojoFailureException("If you are going to skip snapshot but do a restore, you need to specify the snapshotNameOverride")
			}

			if (snapshotNameOverride) {
				realSnapshotName = snapshotNameOverride
				cleanSnapshot = false // never clean this
			}
		}

		if (!skipSnapshot && snapshotNameOverride) {
			throw new MojoFailureException("Snapshoting and overriding the snapshot name make no sense, if you wish to set the snapshot name, use snapshotName")
		}

		init()

		if (intoDatabase) {
			checkIntoDatabaseForSecurityGroupAndVpc()
		}

		if (!skipSnapshot) {
			snapshot()
		}

		DBInstance db = null;

		String hostName

		String sanitizeName = intoDatabase ?: database + "-sanitize"

		if (skipRestore) {
			cleanSanitize = false // never clean this

			if (hostname) {
				hostName = hostname
			} else {
				db = rdsClone.getDatabaseInstance(database)
				if (db == null) {
					throw new MojoFailureException("database ${database} does not exist")
				}
				getLog().info("db endpoint ${db.endpoint.toString()}")
				hostName = db.getEndpoint().address
			}
		} else {
			if (rdsClone.getDatabaseInstance(sanitizeName)) {
				getLog().info("cleaning old database ${sanitizeName} sanitize copy from snapshot")
				rdsClone.deleteDatabaseInstance(sanitizeName, restoreWaitInMinutes, pollTimeInSeconds)
			}

			restoreSnapshot(sanitizeName, realSnapshotName, { boolean success, DBInstance instance ->
				println "instance set"
				db = instance
			})

			println "db instance? ${db}"

			getLog().info("db endpoint ${db.endpoint.toString()}")
			hostName = (db.getEndpoint().address + ":" + db.getEndpoint().port)
		}

		if (!skipSanitize) {
			File sanitizeFolder = new File(project.basedir, sanitizeFolder)
			String command = createCommand(executeSqlCommand, "sanitize sql", hostName)
				.replace('$sanitizeFolder', sanitizeFolder.absolutePath)

			sanitizeSqls.each { String sqlFile ->
				executeSanitizeSql(sqlFile, command, 3)
			}
		}

		if (!skipDump) {
			File dFolder = dumpFolder.startsWith('/') ? new File(dumpFolder) : new File(project.basedir, dumpFolder)
			dFolder.mkdirs()
			String command = createCommand(dumpCommand, "db dump", hostName)
			if (!parallelDump) {
				schemas.forEach({ String schema ->
					dumpSchema(schema, command, dFolder)
				})
			} else {
				schemas.parallelStream().forEach({ String schema ->
					dumpSchema(schema, command, dFolder)
				})
			}
		}

		if (!skipCopy) {

		}

		if (cleanSnapshot) {
			rdsClone.deleteDatabaseSnapshot(realSnapshotName, database, snapshotWaitInMinutes, pollTimeInSeconds)
		}

		if (cleanupDatabaseSnapshots) {
			// no waiting on this one
			rdsClone.removeAttachedSnapshots((skipSanitize && database) ? database : sanitizeName, snapshotName)
		}

		if (cleanSanitize) {
			rdsClone.deleteDatabaseInstance(sanitizeName, 0, 0)
		}
	}

	void executeSanitizeSql( String sqlFile, String command, int retries) {
		getLog().info("SQL File $sqlFile")
		String localCommand = command.replace('$sanitizeSql', sqlFile)
		Process p = splitAndKeepQuotesTogether(localCommand).execute()
		getLog().info(localCommand)
		p.waitFor()

		if (p.exitValue() != 0) {
			String err = p.errorStream?.text
			if (err?.contains('Connection timed out') && retries > 0) {
				getLog().info("Error code ${p.exitValue()} : ${err}, retrying because of connection failure.")
				executeSanitizeSql(sqlFile, command, retries-1)
			} else {
				getLog().info("Error code ${p.exitValue()} : ${err}")
				throw new MojoFailureException("Failed to process request to sanitize")
			}
		}
	}

	private static List<String> splitAndKeepQuotesTogether(String cmd) {
		List<String> commands = [];

		String[] items = cmd.split(' ')
		String element = ''
		boolean inQuote = false

		items.each { String item ->
			if (inQuote) {
				element += ' ' + item.substring(0, item.length() - 1)
				if (item.endsWith("'")) {
					inQuote = false
					commands.add(element)
					element = ''
				}
			} else if (item.startsWith("'")) {
				if (item.endsWith("'")) {
					commands.add(item.substring(1, item.length() - 1))
				} else {
					inQuote = true
					if (element) { element += ' '}
					element += item.substring(1)
				}
			} else {
				commands.add(item.trim())
			}
		}

		if (element) {
			commands.add(element.trim())
		}

		println "returning ${commands}\n${commands.join(' ')}"

		return commands
	}

	private void dumpSchema(String schema, String command, File dFolder) {
		getLog().info("dumping $schema")
		String localCommand = command.replace('$schema', schema)
		Process p = ProcessGroovyMethods.execute(splitAndKeepQuotesTogether(localCommand), [], dFolder)
		def sqlStream = new FileOutputStream(new File(dFolder, "${schema}.sql"))
		def errStream = new FileOutputStream(new File(dFolder, "${schema}.err"))

		if (dumpPrefix) {
			sqlStream.write(dumpPrefix.replace('$schema', schema).bytes)
		}

		p.consumeProcessOutput(sqlStream, errStream)
		p.waitFor()

		if (dumpSuffix) {
			sqlStream.write(dumpSuffix.replace('$schema', schema).bytes)
		}

		sqlStream.flush()
		sqlStream.close()
		errStream.flush()
		errStream.close()

		getLog().info("Exit Value ${p.exitValue()}")
	}
}
