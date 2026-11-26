# GitLab: the project, its runners, its registry, and a GitHub mirror

The repository lives on GitLab, where the pipeline runs on GitLab's own runners and keeps the images in the
project's container registry, and GitHub gets a mirror of every push.

The commands below assume the GitLab and GitHub username `lecorrr`; change it if yours differs.

## 1. Account and project

1. Sign up at [gitlab.com](https://gitlab.com), with an email address or with **Sign in with GitHub**. The first-run
   form also asks for a group and a first project. If the group's address comes out with a number added (another
   user has the name), go on anyway and move the project under your username afterwards: **Settings > General >
   Advanced > Transfer project**. Then delete the empty group; GitLab keeps it for 30 days before removing it.
2. **New project > Create blank project**: name `payment-gateway`, visibility **Public**, and untick
   **Initialize repository with a README** (the repository is pushed from your machine).
3. Add your SSH public key under **User settings > SSH keys > Add new key**. GitLab fills in an expiry date a year
   ahead and emails you before the key expires. Then check that the key works:

   ```bash
   ssh -T git@gitlab.com
   ```

   The first time, ssh asks whether to trust gitlab.com. Answer `yes` only if the ED25519 fingerprint is
   `SHA256:eUXGGm1YGsMAS7vkcx6JOJdOGHPem5gQp4taiCfCLB8`
   ([GitLab's published host keys](https://docs.gitlab.com/user/gitlab_com/#ssh-host-keys-fingerprints)). The
   answer ends with `Welcome to GitLab, @lecorrr!`.

GitLab may ask you to verify the account with a phone number. GitLab lists phone verification for Ukraine as partial
support, so if the code never arrives, use the card option instead.

## 2. GitLab's runners

The jobs ask for GitLab's small Linux runner by its tag, `saas-linux-small-amd64`. It runs each job in a fresh
virtual machine and allows the privileged containers that the jobs' Docker-in-Docker needs.

1. In the project, **Settings > CI/CD > Runners**: turn on **Instance runners**. A new project has them on.
2. GitLab runs a new account's jobs on its runners only once the account is validated. Until then the pipeline fails
   with a message that asks you to validate the account, and a button for it. GitLab checks a credit or debit card
   there and says it doesn't charge it. Then retry the pipeline.
3. A free account gets 400 compute minutes a month on these runners. **User settings > Usage quotas** shows how many
   are used. A pipeline on main takes the most: besides the build and the checks, its e2e job runs a Kubernetes
   cluster for the end-to-end check, and is the longest job. A newer push to the same branch cancels the jobs of the
   pipeline before it that haven't finished, except a release.

A runner on your own machine would work too, without compute minutes. But GitLab shows the internet address a runner
connects from in the details of every job it runs, and in a public project anyone can read them. The tag keeps this
project's jobs off such a runner.

## 3. First push

```bash
git remote add origin git@gitlab.com:lecorrr/payment-gateway.git
```

```bash
git push -u origin main
```

GitLab protects `main` on the first push: only Maintainers push or merge, and force pushes are off. The pipeline
starts by itself (**Build > Pipelines**). Each job starts on a fresh machine and pulls its images; the Maven
dependencies come from GitLab's cache after the first run.

## 4. Mirror to GitHub

1. On GitHub, create an empty public repository `payment-gateway`: no README, licence or .gitignore, since
   everything in it comes from GitLab.
2. On GitHub, **Settings > Developer settings > Personal access tokens > Fine-grained tokens > Generate new
   token**. GitHub first asks you to confirm it's you, with GitHub Mobile or a code sent by email; GitHub Mobile
   only reaches the phone it is signed in on. Then:
   - Expiration: **Custom**, up to a year ahead. GitHub emails you before the token expires.
   - Repository access: **Only select repositories**, with `payment-gateway`.
   - Permissions: **Add permissions > Contents**, set to **Read and write**. GitHub adds read-only Metadata itself.
     The repository has no GitHub workflows, so nothing else is needed.

   **Generate token** and copy it: GitHub shows it only once.
3. On GitLab, **Settings > Repository > Mirroring repositories > Add new**:
   - Git repository URL: `https://github.com/lecorrr/payment-gateway.git`
   - Mirror direction: **Push**
   - Authentication method: **Username and Password**, username `lecorrr`, and the token as the password. Not your
     GitHub password: GitHub doesn't accept account passwords for git.
4. **Mirror repository**, then **Update now** for the first push. From then on every push to GitLab reaches GitHub
   by itself.

Push only to GitLab. **Keep divergent refs** stays off, so anything pushed straight to GitHub is overwritten at the
next sync.

When the token expires, the mirror shows an error. A mirror's password can't be edited, so make a new token, remove
the mirror and add it again.

## 5. Container registry

On main, the `images` job pushes the four apps' images to the project's own container registry, tagged with the
commit's SHA, and once every check has passed the `release` job gives them the version from `pom.xml` too:
`registry.gitlab.com/lecorrr/payment-gateway/gateway-service:0.8.0`, as `values-prod.yaml` names them. The jobs
sign in with the job's own token, so there is nothing to set up for that. **Deploy > Container registry** lists the
images and their tags.

- **Who can pull.** In a public project anyone can, without signing in, which is what lets a cluster or a laptop
  run a released version as it is (the README's Kubernetes section says how). **Settings > General > Visibility,
  project features, permissions > Container registry** can limit it to project members.
- **Old tags.** Every push to main adds four commit tags. A cleanup policy removes the old ones and keeps the
  versions: **Settings > Packages and registries > Container registry > Set cleanup rules**, with the policy on,
  **Run cleanup** every week, **Keep the most recent** 5 tags per image, **Keep tags matching** `\d+\.\d+\.\d+`,
  **Remove tags older than** 14 days and **Remove tags matching** `.*`. GitLab reads the patterns as whole tags, so
  `\d+\.\d+\.\d+` keeps `0.8.0` and nothing longer. **Settings > Usage quotas** shows the registry's size.
- **Releases.** A version is released once: a later commit with the same version in `pom.xml` keeps only its commit
  tag, and the `release` job says so. Raise the version to release again.

## 6. Merge requests

For changes that go through merge requests rather than straight to main, the pipeline runs for the merge request
(build, tests, static analysis, chart, secrets and the images' scan; the images aren't pushed). To merge only what
passed, turn on **Settings > Merge requests > Merge checks > Pipelines must succeed** (on older GitLab versions,
under **Settings > General > Merge requests**). The e2e check and the release run after the merge, on main.

## When a job fails

- **The job is stuck, with no runner for its tags**: instance runners are off for the project (section 2).
- **The pipeline asks you to validate the account**: section 2, step 2.
- **Cannot connect to the Docker daemon at tcp://docker:2375**: the job's Docker-in-Docker service didn't start.
  Run the pipeline again from **Build > Pipelines > New pipeline** with the variable `CI_DEBUG_SERVICES` set to
  `true`, and the job log shows the service's own log.
- **toomanyrequests** from Docker Hub: an image that Google's copy of Docker Hub lacks came from Docker Hub, which
  limits anonymous pulls per IP address, and GitLab's runners share theirs. Retry the job later.
- **A job stops before its work, in `ci/install-tools.sh`**: a download that broke off, or a file whose checksum
  isn't the published one (the script pins each tool's version and, for the tools released on GitHub, its
  checksum). Retry the job once; a checksum that still doesn't match means the file is not the release it claims to
  be.
- **verify fails at `spotbugs:check`**: SpotBugs or FindSecBugs found a bug pattern of medium priority or above. The
  log names the pattern, the class and the line, and the job's artifacts have each module's
  `target/spotbugsXml.xml`. Fix the code; if the finding is wrong, add a match to `spotbugs-exclude.xml` with a
  comment that says why.
- **secrets fails**: gitleaks found what looks like a key, token or password. Its log names the commit, the file and
  the rule, with the secret itself hidden. A real secret stays in the history even after a commit deletes it, so
  revoke it and make a new one first. If it's not a secret (a made-up value outside the tests), add it to the
  allowlist in `.gitleaks.toml`.
- **images fails because Trivy found vulnerabilities**: its table names each package, the version in the image and
  the version that fixes it. A Java library is fixed by a newer version: usually the next Spring Boot patch release,
  or a version property of Spring Boot's (such as `tomcat.version`) in `pom.xml` until then. A system package of the
  Java base image is fixed when Eclipse Temurin publishes a rebuilt `eclipse-temurin:21-jre`, usually within days;
  the job pulls the base image afresh each time, so retry it then. Only a finding that doesn't apply to these apps
  belongs in a `.trivyignore.yaml`, each entry with its reason and an expiry date.
- **images fails with "Trivy could not scan"**: Trivy couldn't download its vulnerability database. Retry the job.
- **images fails at `docker push` with `denied`**: the project's container registry is off. Turn it on under
  **Settings > General > Visibility, project features, permissions > Container registry**.
- **e2e fails**: the job's artifacts (**Browse** on the job's page) have `e2e/smoke-test.log`, the cluster's
  objects and events, and every pod's log. `deploy/k3d/smoke-test.sh` checks the same on a laptop's k3d cluster
  (the README's Kubernetes section); with `IMAGE_REGISTRY` and `IMAGE_TAG`, `deploy.sh` runs the very images that
  failed.
