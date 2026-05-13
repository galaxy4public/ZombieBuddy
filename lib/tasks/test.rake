def run_tests task
  env = {
    # "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-24.jdk/Contents/Home"
  }
  cp_root = File.join(PROJECT_ROOT, "versions/unstable/java")
  cp = [File.join(cp_root, "projectzomboid.jar")].join(",")
  props = {
    :gameClasspath  => cp,
    "zb.verbosity"  => 2,
    :showStreams    => true,
  }

  cmd = ["gradle", task, "--info", *props.map{ |k,v| "-P#{k}=#{v}" }]

  if ENV['TESTS']
    cmd << "--tests" << ENV['TESTS']
  end

  Dir.chdir("java") do
    sh env, *cmd
  end
end

namespace :test do
  desc 'run unit tests'
  task :unit do
    run_tests 'unitTest'
  end
end

desc 'run all tests'
task :test do
  run_tests 'test'
end
