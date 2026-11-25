# GitLab: the project, its runners, and a GitHub mirror

The repository lives on GitLab, where the pipeline runs on GitLab's own runners, and GitHub gets a mirror of every
push.

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
   are used.

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

## When a job fails

- **The job is stuck, with no runner for its tags**: instance runners are off for the project (section 2).
- **The pipeline asks you to validate the account**: section 2, step 2.
- **Cannot connect to the Docker daemon at tcp://docker:2375**: the job's Docker-in-Docker service didn't start.
  Run the pipeline again from **Build > Pipelines > New pipeline** with the variable `CI_DEBUG_SERVICES` set to
  `true`, and the job log shows the service's own log.
- **toomanyrequests** from Docker Hub: an image that Google's copy of Docker Hub lacks came from Docker Hub, which
  limits anonymous pulls per IP address, and GitLab's runners share theirs. Retry the job later.
