#!/usr/bin/groovy
import groovy.xml.*
import groovy.xml.dom.*

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def flow = new io.fabric8.Fabric8Commands()

    for(int i = 0; i < config.projects.size(); i++){
      def project = config.projects[i]
      def items = project.split( '/' )
      def org = items[0]
      def repo = items[1]

      stage "Updating ${project}"
      sh "rm -rf ${repo}"
      sh "git clone https://github.com/${project}.git"
      sh "cd ${repo} && git remote set-url origin git@github.com:${project}.git"

      def uid = UUID.randomUUID().toString()
      sh "cd ${repo} && git checkout -b versionUpdate${uid}"

      def xml = readFile file: "${repo}/pom.xml"
      sh "cat ${repo}/pom.xml"

      def pom = updateParentVersion (xml, config.version)

      writeFile file: "${repo}/pom.xml", text: pom

      sh "cat ${repo}/pom.xml"

      kubernetes.pod('buildpod').withImage('fabric8/maven-builder:latest')
        .withPrivileged(true)
        .withSecret('jenkins-git-ssh','/root/.ssh-git')
        .withSecret('jenkins-ssh-config','/root/.ssh')
        .inside {

          sh 'chmod 600 /root/.ssh-git/ssh-key'
          sh 'chmod 600 /root/.ssh-git/ssh-key.pub'
          sh 'chmod 700 /root/.ssh-git'

          sh "git config --global user.email fabric8-admin@googlegroups.com"
          sh "git config --global user.name fabric8-release"

          def githubToken = flow.getGitHubToken()
          def message = "\"Update parent pom version ${config.version}\""
          sh "cd ${repo} && git add pom.xml"
          sh "cd ${repo} && git commit -m ${message}"
          sh "cd ${repo} && git push origin versionUpdate${uid}"
          sh "export GITHUB_TOKEN=${githubToken} && cd ${repo} && hub pull-request -m ${message} > pr.txt"
      }
      pr = readFile("${repo}/pr.txt")
      split = pr.split('\\/')
      def prId = split[6].trim()
      echo "received Pull Request Id: ${prId}"
      flow.addMergeCommentToPullRequest(prId, project)
    }
  }

  @NonCPS
  def updateParentVersion(xml, newVersion) {
    def index = xml.indexOf('<project')
    def header = xml.take(index)
    def xmlDom = DOMBuilder.newInstance().parseText(xml)
    def root = xmlDom.documentElement
    use (DOMCategory) {
      root.parent.version*.setTextContent(newVersion)
      def newXml = XmlUtil.serialize(root)

      // need to fix this, we get errors above then next time round if this is left in
      return header + newXml.minus('<?xml version="1.0" encoding="UTF-8"?>')
    }
  }
