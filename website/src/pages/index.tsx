import type {ReactNode} from 'react';
import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import CodeBlock from '@theme/CodeBlock';

function HeroSection() {
  return (
    <header className="hero hero--rebound">
      <div className="container">
        <Heading as="h1" className="hero__title">
          Catch runaway recompositions before they ship
        </Heading>
        <p className="hero__subtitle">
          Budget-based recomposition monitoring for Jetpack Compose.
          Different composables get different budgets. A Screen at 3/s.
          An Animation at 120/s. Zero config. Debug builds only.
        </p>
        <div style={{marginTop: '2rem'}}>
          <Link
            className="button button--primary button--lg"
            to="/docs/intro">
            Get Started
          </Link>
        </div>
      </div>
    </header>
  );
}

type FeatureCardProps = {
  title: string;
  description: string;
};

function FeatureCard({title, description}: FeatureCardProps) {
  return (
    <div className="col col--4">
      <div className="feature-card">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

function FeaturesSection() {
  return (
    <section className="feature-cards">
      <div className="container">
        <div className="row">
          <FeatureCard
            title="Budget Classes"
            description="Different composables get different budgets. A Screen at 3/s, a Container at 8/s, a Leaf at 5/s, an Animation at 120/s. Flat thresholds are wrong for most of your composables. Budgets match the role."
          />
          <FeatureCard
            title="IDE Cockpit"
            description="Five-tab performance dashboard inside Android Studio. Live monitoring with sparklines. Hot spot ranking by budget ratio. Timeline heatmaps. Per-parameter stability matrix. Inline gutter icons in the editor."
          />
          <FeatureCard
            title="Zero Config"
            description="Three lines in your build file. The compiler plugin instruments debug builds automatically. No annotation required. KMP ready -- Android, JVM, iOS, and Wasm. No overhead in release builds."
          />
        </div>
      </div>
    </section>
  );
}

function CodePreviewSection() {
  const gradleCode = `plugins {
    id("io.aldefy.rebound") version "0.1.0-SNAPSHOT"
}`;

  return (
    <section className="code-preview">
      <div className="container">
        <Heading as="h2">Three lines to get started</Heading>
        <p style={{marginBottom: '1.5rem', opacity: 0.8}}>
          Add the Gradle plugin. Build in debug. That is it.
        </p>
        <CodeBlock language="kotlin" title="build.gradle.kts">
          {gradleCode}
        </CodeBlock>
      </div>
    </section>
  );
}

export default function Home(): ReactNode {
  return (
    <Layout
      title="Compose Recomposition Budget Monitor"
      description="Budget-based recomposition monitoring for Jetpack Compose. Different composables get different budgets.">
      <HeroSection />
      <main>
        <FeaturesSection />
        <CodePreviewSection />
      </main>
    </Layout>
  );
}
