# GitLab: the project, a runner on your desktop, and a GitHub mirror

The repository lives on GitLab, where the pipeline runs. The jobs run on a runner on your own desktop, so they
use none of GitLab's compute minutes, and GitHub gets a mirror of every push.

The commands below assume the GitLab and GitHub username `lecorrr`; change it if yours differs.

## 1. Account and project

1. Sign up at [gitlab.com](https://gitlab.com), with an email address or with **Sign in with GitHub**. The first-run
   form also asks for a group and a first project. If the group's address comes out with a number added (another
   user has the name), go on anyway and move the project under your username afterwards: **Settings > General >
   Advanced > Transfer project**. Then delete the empty group; GitLab keeps it for 30 days before removing it.
2. **New project > Create blank project**: name `payment-gateway`, visibility **Public**, and untick
   **Initialize repository with a README** (the repository is pushed from your machine).
3. In the project, **Settings > CI/CD > Runners**: turn off **Instance runners**, so jobs wait for your runner and
   never use compute minutes.
4. Add your SSH public key under **User settings > SSH keys > Add new key**. GitLab fills in an expiry date a year
   ahead and emails you before the key expires. Then check that the key works:

   ```bash
   ssh -T git@gitlab.com
   ```

   The first time, ssh asks whether to trust gitlab.com. Answer `yes` only if the ED25519 fingerprint is
   `SHA256:eUXGGm1YGsMAS7vkcx6JOJdOGHPem5gQp4taiCfCLB8`
   ([GitLab's published host keys](https://docs.gitlab.com/user/gitlab_com/#ssh-host-keys-fingerprints)). The
   answer ends with `Welcome to GitLab, @lecorrr!`.

GitLab may ask you to verify the account before the first pipeline. GitLab lists phone verification for Ukraine as
partial support, so if the code never arrives, use the card option instead.

## 2. The runner

The runner itself runs in a container and starts each job as another container on your desktop's Docker. The jobs
use Docker-in-Docker for Testcontainers, which needs privileged containers, so the runner takes jobs from this
project only, and only from protected branches.

1. **Settings > CI/CD > Runners > Create project runner**:
   - tick **Run untagged jobs** (the jobs in `.gitlab-ci.yml` have no tags);
   - tick **Protected**, so only protected branches such as `main` run on it. A pipeline for someone else's merge
     request then never runs on your machine, even if it is started in this project;
   - tick **Lock to current projects**;
   - description: the machine, for example `archi desktop`.

   Then **Create runner**. GitLab shows a token starting with `glrt-`. Leave that page open: the token goes into the
   register command below and nowhere else.
2. Make a volume for the runner's configuration:

   ```bash
   docker volume create gitlab-runner-config
   ```

3. Start the runner, restarting with Docker:

   ```bash
   docker run -d --name gitlab-runner --restart always -v /var/run/docker.sock:/var/run/docker.sock -v gitlab-runner-config:/etc/gitlab-runner gitlab/gitlab-runner:latest
   ```

4. Register it:

   ```bash
   docker run --rm -it -v gitlab-runner-config:/etc/gitlab-runner gitlab/gitlab-runner:latest register --url https://gitlab.com --executor docker --docker-image eclipse-temurin:21-jdk --docker-privileged --docker-pull-policy if-not-present
   ```

   It asks for the URL (press Enter), the token (paste it), a name (for example `archi`), and the executor and the
   image (press Enter for both). `if-not-present` lets jobs reuse the images already on your desktop instead of
   pulling them each time.

   The terminal shows the token as you paste it, so don't share that output. If the token gets out anyway, replace
   it. This gets a new token from GitLab and saves it in the runner's configuration; the old one stops working:

   ```bash
   docker run --rm -v gitlab-runner-config:/etc/gitlab-runner gitlab/gitlab-runner:latest reset-token --name archi
   ```

   Then restart the runner so it uses the new token:

   ```bash
   docker restart gitlab-runner
   ```

5. The runner shows as online on the **Runners** page. Its log, if it doesn't:

   ```bash
   docker logs --tail 50 gitlab-runner
   ```

To stop it, `docker stop gitlab-runner`. To remove it for good: `docker rm -f gitlab-runner`, then
`docker volume rm gitlab-runner-config`, then delete the runner on the **Runners** page.

## 3. First push

```bash
git remote add origin git@gitlab.com:lecorrr/payment-gateway.git
```

```bash
git push -u origin main
```

GitLab protects `main` on the first push (only Maintainers push or merge, and force pushes are off), so its pipeline
runs on the protected runner. The pipeline starts by itself (**Build > Pipelines**). The first run pulls the images
and the Maven dependencies and takes a few minutes; later runs use the runner's cache.

Other branches aren't protected, so their jobs wait without a runner. To run them on your desktop as well, protect
them too in **Settings > Repository > Protected branches**, for example with a `feature/*` wildcard.

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

- **The job waits in pending with no runner**: the runner is offline (see its log above), or the branch isn't
  protected and the runner only takes protected branches.
- **Cannot connect to the Docker daemon at tcp://docker:2375**: the runner is not privileged. Set
  `privileged = true` under `[runners.docker]` in the runner's `config.toml` (in the `gitlab-runner-config`
  volume), then `docker restart gitlab-runner`.
- **toomanyrequests** from Docker Hub: each pipeline pulls a few images into its fresh Docker-in-Docker, and Docker
  Hub limits anonymous pulls per IP address. Wait for the limit to reset, or log the runner in to Docker Hub.
