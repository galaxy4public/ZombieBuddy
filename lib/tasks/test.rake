namespace :test do
  desc 'run unit tests'
  task :unit do
    env = {
      "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-24.jdk/Contents/Home"
    }
    cp_root = File.join(PROJECT_ROOT, "versions/unstable/java")
    cp = [File.join(cp_root, "projectzomboid.jar")].join(",")

    cmd = ["gradle", "unitTest", "--info", "-PgameClasspath=#{cp}", "-Pzb.verbosity=2"]

    if ENV['TESTS']
      cmd << "--tests" << ENV['TESTS']
    end

    Dir.chdir("java") do
      sh env, *cmd
    end
  end
end

desc 'run all tests'
task :test do
    env = {
      "JAVA_HOME" => "/Library/Java/JavaVirtualMachines/openjdk-24.jdk/Contents/Home"
    }
    cp_root = File.join(PROJECT_ROOT, "versions/unstable/java")
    cp = [File.join(cp_root, "projectzomboid.jar")].join(",")

    cmd = ["gradle", "test", "--info", "-PgameClasspath=#{cp}", "-Pzb.verbosity=2"]

    if ENV['TESTS']
      cmd << "--tests" << ENV['TESTS']
    end

    Dir.chdir("java") do
      sh env, *cmd
    end
end
