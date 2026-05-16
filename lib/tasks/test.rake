def run_tests task
  env = {
    "ZB_VERBOSITY" => "2", # for unit tests that run without Agent, but with Logger
  }
  cp_root = File.join(PROJECT_ROOT, "versions/unstable/java")
  cp = [File.join(cp_root, "projectzomboid.jar")].join(",")
  props = {
    :gameClasspath => cp,
    :showStreams   => true,
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
