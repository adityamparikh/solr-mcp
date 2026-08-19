To release Solr MCP, these are the steps:

1) Create a release branch like branch_1_0_0.  Or update the existing release branch with latest changes.

1) Switch to that branch.

1) Deal with `-SNAPSHOT` build artifacts.  For 1.0 we will merge https://github.com/apache/solr-mcp/pull/136/changes into the branch.

1) Set up a release in Apache Trusted Release server at https://release-test.apache.org/#project-solr-mcp.

1) create the binary artifact via `./gradlew clean build`

1) create the source artifact.  The source release is the artifact the PMC actually votes on, and the build does not produce one, so make it explicitly from the exact commit being voted on:
```
export V=1.0.0
git archive --format=tar.gz --prefix=solr-mcp-$V/ HEAD -o build/libs/solr-mcp-$V-src.tgz
```

1) `./gradlew build` already ran the Apache RAT license-header check (`org.apache.solr.mcp.rat`) and regenerated the binary `LICENSE`/`NOTICE` as part of `check` — confirm the build was green, then spot-check the generated files bundled in the bootJar:
```
unzip -p build/libs/solr-mcp-$V.jar META-INF/LICENSE
unzip -p build/libs/solr-mcp-$V.jar META-INF/NOTICE
```

1) Collect just the two artifacts being released.  `build/libs` also holds `-plain`, `-sources` and `-javadoc` jars, which are not part of the release, so do not glob the whole directory:
```
cd build/libs
mkdir -p ../release && cp solr-mcp-$V.jar solr-mcp-$V-src.tgz ../release/
cd ../release
```

1) Sign them via (`gpgsign.sh` comes from the ASF release tooling, not this repo):
```
for fn in *
do
  gpgsign.sh sign ~/.ssh/.private.asc "$fn"
done
```

1) Make sha keys via:
```
for fn in *.jar *.tgz
do
  shasum -a 512 $fn > $fn.sha512
done
```

1) Upload all the artifacts to the previously created release in ATR.

1) Test in Claude Code or Claude Desktop using the steps in ./dev-docs/SMOKE_TEST.md.

1) After the vote, create a tag with the source code in github, as `releases/solr-mcp/1.0.0`

1) After the vote bump the version tag in `main` for 1.1 so we get -snapshot builds there.
