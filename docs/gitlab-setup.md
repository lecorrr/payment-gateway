# GitLab: the project, a runner on your desktop, and a GitHub mirror

The repository lives on GitLab, where the pipeline runs. The jobs run on a runner on your own desktop, so they
use none of GitLab's compute minutes, and GitHub gets a read-only mirror of every push.

The commands below assume the GitLab username `lecorrr`; change it if yours differs.

## 1. Account and project

1. Sign up at [gitlab.com](https://gitlab.com) with the email your commits use, and confirm it.
2. Add your SSH public key under **Edit profile > SSH Keys**, then check that it works:

   ```bash
   ssh -T git@gitlab.com
   ```

3. **New project > Create blank project**: name `payment-gateway`, visibility **Public**, and untick
   **Initialize repository with a README** (the repository is pushed from your machine).
4. In the project, **Settings > CI/CD > Runners**: turn off **Instance runners** for the project, so jobs wait for
   your runner and never use compute minutes.

GitLab may ask you to verify the account before the first pipeline. GitLab lists phone verification for Ukraine as
partial support, so if the code never arrives, use the card option instead.

## 2. The runner

The runner itself runs in a container and starts each job as another container on your desktop's Docker. The jobs
use Docker-in-Docker for Testcontainers, which needs privileged containers: this runner is for this project only,
so only your own pushes run on it (pipelines of forks run in the fork).

1. **Settings > CI/CD > Runners > Create project runner**: tick **Run untagged jobs**, then **Create runner**.
   GitLab shows a token starting with `glrt-` once. Leave that page open; the token goes into the register command
   below, and nowhere else.
2. Make a volume for the runner's configuration:

   ```bash
   docker volume create gitlab-runner-config
   ```

3. Start the runner, restarting with Docker:

   ```bash
   docker run -d --name gitlab-runner --restart always -v /var/run/docker.sock:/var/run/docker.sock -v gitlab-runner-config:/etc/gitlab-runner gitlab/gitlab-runner:latest
   ```

4. Register it. It asks for the token (paste it there) and a name (for example `archi`); press Enter to keep the
   other values given on the command line:

   ```bash
   docker run --rm -it -v gitlab-runner-config:/etc/gitlab-runner gitlab/gitlab-runner:latest register --url https://gitlab.com --executor docker --docker-image eclipse-temurin:21-jdk --docker-privileged --docker-pull-policy if-not-present
   ```

   `if-not-present` lets jobs reuse the images already on your desktop instead of pulling them each time.

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

The pipeline starts by itself (**Build > Pipelines**). The first run downloads Maven and the dependencies and takes
a few minutes; later runs use the runner's cache.

## 4. Mirror to GitHub

1. On GitHub, create an empty public repository `payment-gateway` (no README, licence or .gitignore).
2. On GitHub, **Settings > Developer settings > Personal access tokens > Fine-grained tokens > Generate new
   token**: repository access **Only select repositories** with `payment-gateway`, and the permission
   **Contents: Read and write**. The repository has no GitHub workflows, so nothing else is needed.
3. On GitLab, **Settings > Repository > Mirroring repositories > Add new**:
   - Git repository URL: `https://github.com/lecorrr/payment-gateway.git`
   - Mirror direction: **Push**
   - Authentication method: **Username and Password**, username `lecorrr`, password the GitHub token
4. **Mirror repository**, then **Update now** for the first push. From then on every push to GitLab reaches GitHub
   within minutes. When the token expires, the mirror shows an error: make a new token and update the password.

## When a job fails

- **Cannot connect to the Docker daemon at tcp://docker:2375**: the runner is not privileged. Set
  `privileged = true` under `[runners.docker]` in the runner's `config.toml` (in the `gitlab-runner-config`
  volume), then `docker restart gitlab-runner`.
- **toomanyrequests** from Docker Hub: each pipeline pulls a few images into its fresh Docker-in-Docker, and Docker
  Hub limits anonymous pulls per IP address. Wait for the limit to reset, or log the runner in to Docker Hub.
