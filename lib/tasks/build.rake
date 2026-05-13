desc 'build'
task :build => ["build:unstable", "build:42_12", "sign_authors"]

desc 'clean the project'
task :clean do
  Dir.chdir("java") do
    sh "gradle clean -PjavaVersion=25"
    sh "gradle clean -PjavaVersion=17"
  end
end

namespace :build do
  # runs first
  desc 'build'
  task :unstable do
    cp_root = File.join(PROJECT_ROOT, "versions/unstable/java")
    cp = [File.join(cp_root, "projectzomboid.jar")].join(",")
 
    Dir.chdir("java") do
      sh "gradle build --warning-mode all -PjavaVersion=25 -PgameClasspath=#{cp}"
    end
  end

  # runs last, result is the final build
  desc 'build'
  task "42_12" do
    cp_root = File.join(PROJECT_ROOT, "versions/42.12/java")
    cp = build_classpath(cp_root).join(",")

    Dir.chdir("java") do
      sh "gradle build --warning-mode all -PjavaVersion=17 -PgameClasspath=#{cp}"
    end
  end
end

desc 'sign authors'
task :sign_authors do
  Dir.chdir("java") do
    sh "gradle signAuthorsJson"
  end
end
